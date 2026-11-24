import client.MapleCharacter;
import client.MapleClient;
import client.Skill;
import client.status.MonsterStatus;
import client.status.MonsterStatusEffect;
import config.YamlConfig;
import constants.skills.NightWalker;
import constants.skills.Rogue;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.server.channel.Channel;
import net.opcodes.SendOpcode;
import net.server.Server;
import net.server.services.Service;
import net.server.services.ServicesManager;
import net.server.services.BaseScheduler;
import net.server.services.task.channel.MobStatusService;
import net.server.services.type.ChannelServices;
import protocol.generated.maple.map.MobStatusOperation;
import protocol.generated.maple.map.MobStatusUpdate;
import protocol.generated.maple.map.MobStatusUpdateEntry;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.life.Element;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.life.MobSkill;
import server.maps.MapleMap;
import server.TimerManager;
import tools.packets.map.MobStatusPackets;
import tools.MaplePacketCreator;

/** Offline lifecycle test; only the channel/scheduler and delivery sink are isolated. */
public class MobStatusLifecycleTest {
    static final class Scheduler extends MobStatusService {
        final Map<MonsterStatusEffect, Runnable> actions = new IdentityHashMap<>();
        final Map<MonsterStatusEffect, Long> remaining = new IdentityHashMap<>();
        @Override public void registerMobStatus(int map, MonsterStatusEffect effect, Runnable cancel,
                                                long duration, Runnable overtime, int delay) {
            actions.put(effect, cancel);
            remaining.put(effect, duration);
        }
        @Override public void registerMobStatus(int map, MonsterStatusEffect effect, Runnable cancel, long duration) {
            registerMobStatus(map, effect, cancel, duration, null, -1);
        }
        @Override public void interruptMobStatus(int map, MonsterStatusEffect effect) {
            Runnable action = actions.remove(effect);
            remaining.remove(effect);
            if (action != null) action.run();
        }
        @Override public long remainingMobStatusMillis(int map, MonsterStatusEffect effect) {
            return remaining.getOrDefault(effect, 0L);
        }
    }
    static final Scheduler scheduler = new Scheduler();
    static final class TestMap extends MapleMap {
        final List<byte[]> packets = Collections.synchronizedList(new ArrayList<>());
        Channel channel;
        volatile TestClient recipient;
        volatile boolean exerciseMapLocks;
        TestMap() { super(100000100, 0, 1, 100000000, 1); }
        @Override public Channel getChannelServer() { return channel; }
        @Override public void broadcastMessage(byte[] packet, Point position) {
            if (exerciseMapLocks) super.broadcastMessage(packet, position);
            packets.add(packet);
            TestClient client = recipient;
            if (client != null) client.announce(packet);
        }
    }
    static class TestClient extends MapleClient {
        final List<byte[]> packets = Collections.synchronizedList(new ArrayList<>());
        TestClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) { packets.add(packet); }
    }
    static final class BlockingSpawnClient extends TestClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public void announce(byte[] packet) {
            if (entered.getCount() != 0) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("spawn release timeout");
                } catch (InterruptedException e) { throw new AssertionError(e); }
            }
            super.announce(packet);
        }
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static Skill skill(int id) {
        Skill skill = new Skill(id);
        skill.setElement(Element.NEUTRAL);
        skill.setAnimationTime(100);
        return skill;
    }
    static MonsterStatusEffect effect(int skill, Map<MonsterStatus, Integer> values) {
        return new MonsterStatusEffect(values, skill(skill), null, false);
    }
    static MobStatusUpdate flat(byte[] bytes) {
        if (bytes.length < 8) return null;
        ByteBuffer data = ByteBuffer.wrap(bytes);
        if (!Packet.PacketBufferHasIdentifier(data)) return null;
        Packet packet = Packet.getRootAsPacket(data);
        check(packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobStatusUpdate, "union");
        MobStatusUpdate update = new MobStatusUpdate();
        packet.serverMessage(update);
        return update;
    }
    static MobStatusUpdate lastFlat(List<byte[]> packets) {
        for (int i = packets.size() - 1; i >= 0; i--) {
            MobStatusUpdate update = flat(packets.get(i));
            if (update != null) return update;
        }
        throw new AssertionError("no status update");
    }
    static MobStatusUpdateEntry entry(MobStatusUpdate update, MonsterStatus status) {
        for (int i = 0; i < update.entriesLength(); i++) {
            MobStatusUpdateEntry entry = update.entries(i);
            if (entry.statusId().equals(status.name())) return entry;
        }
        throw new AssertionError("missing " + status);
    }
    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
        List<MobStatusPackets.Entry> all = new ArrayList<>();
        for (MonsterStatus status : MonsterStatus.values()) all.add(new MobStatusPackets.Entry(status, -1, 123, 42));
        MobStatusUpdate allWire = flat(MobStatusPackets.update(100000100, 1, MobStatusOperation.Apply, all));
        check(allWire.entriesLength() == MonsterStatus.values().length, "all status identifiers serialized");
        for (MonsterStatus status : MonsterStatus.values()) check(entry(allWire, status).value() == -1, status.name());
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        TestMap map = new TestMap();
        // Avoid the socket-bound Channel constructor while retaining its real service lookup.
        map.channel = (Channel) unsafe.allocateInstance(Channel.class);
        ServicesManager services = (ServicesManager) unsafe.allocateInstance(ServicesManager.class);
        Service<?> access = (Service<?>) unsafe.allocateInstance(Service.class);
        Field cls = Service.class.getDeclaredField("cls");
        cls.setAccessible(true); cls.set(access, MobStatusService.class);
        Field service = Service.class.getDeclaredField("service");
        service.setAccessible(true); service.set(access, scheduler);
        Service<?>[] entries = new Service<?>[ChannelServices.values().length];
        entries[ChannelServices.MOB_STATUS.ordinal()] = access;
        Field serviceArray = ServicesManager.class.getDeclaredField("services");
        serviceArray.setAccessible(true); serviceArray.set(services, entries);
        Field channelServices = Channel.class.getDeclaredField("services");
        channelServices.setAccessible(true); channelServices.set(map.channel, services);
        MapleMonsterStats stats = new MapleMonsterStats();
        stats.setHp(1000);
        MapleMonster mob = new MapleMonster(100100, stats);
        mob.setObjectId(123);
        mob.setMap(map);

        MonsterStatusEffect old = effect(Rogue.DISORDER, Map.of(MonsterStatus.WATK, -8, MonsterStatus.WDEF, -6));
        check(mob.applyStatus(null, old, false, 5000), "initial disorder");
        MobStatusUpdate update = lastFlat(map.packets);
        check(update.mapId() == 100000100 && update.mobOid() == 123 && update.operation() == MobStatusOperation.Apply, "routing");
        check(!update.snapshot(), "application is not a spawn snapshot");
        check(entry(update, MonsterStatus.WATK).value() == -8 && entry(update, MonsterStatus.WDEF).value() == -6, "flat signed values");
        check(entry(update, MonsterStatus.WATK).sourceSkillId() == Rogue.DISORDER, "source");
        check(!mob.applyStatus(null, effect(NightWalker.DISORDER, Map.of(MonsterStatus.WATK, -20)), false, 6000), "no variant refresh");

        // Partial replacement preserves old WDEF and makes its captured expiration safe.
        Runnable oldExpiration = scheduler.actions.get(old);
        Map<MonsterStatus, MonsterStatusEffect> oldSnapshot = mob.getStati();
        MonsterStatusEffect newer = effect(123456, Map.of(MonsterStatus.WATK, 17, MonsterStatus.STUN, 1));
        check(mob.applyStatus(null, newer, false, 7000), "partial replacement");
        check(oldSnapshot.get(MonsterStatus.WATK).getStati().get(MonsterStatus.WATK) == -8,
                "status snapshot survives replacement");
        scheduler.remaining.put(old, 2200L);
        scheduler.remaining.put(newer, 4500L);
        TestClient observer = new TestClient();
        mob.sendSpawnWithStatuses(observer, () -> new byte[]{1, 2});
        check(observer.packets.size() == 2 && observer.packets.get(0)[0] == 1, "spawn precedes snapshot");
        update = lastFlat(observer.packets);
        check(update.snapshot(), "spawn status is marked as a snapshot");
        check(entry(update, MonsterStatus.WDEF).durationMs() == 2200
                && entry(update, MonsterStatus.STUN).durationMs() == 4500, "per-effect remaining lifetime");
        Map<MonsterStatus, MonsterStatusEffect> beforeExpiry = mob.getStati();
        oldExpiration.run();
        check(mob.getStati(MonsterStatus.WATK) == newer && !mob.isBuffed(MonsterStatus.WDEF), "old expiration identity");
        check(beforeExpiry.get(MonsterStatus.WDEF).getStati().get(MonsterStatus.WDEF) == -6
                && mob.getStatusValue(MonsterStatus.WDEF) == null, "stable expired snapshot and atomic value");
        update = lastFlat(map.packets);
        check(update.operation() == MobStatusOperation.Cancel && update.entriesLength() == 1
                && entry(update, MonsterStatus.WDEF).value() == -6, "partial cancel");

        mob.applyMonsterBuff(Map.of(MonsterStatus.WEAPON_ATTACK_UP, 40, MonsterStatus.MAGIC_DEFENSE_UP, 9),
                0, 100, 9000, new MobSkill(100, 1), null);
        update = lastFlat(map.packets);
        check(entry(update, MonsterStatus.WEAPON_ATTACK_UP).sourceSkillId() == 0, "mob source");
        Method debuff = MapleMonster.class.getDeclaredMethod("debuffMobStat", MonsterStatus.class);
        debuff.setAccessible(true);
        debuff.invoke(mob, MonsterStatus.WEAPON_ATTACK_UP);
        check(mob.isBuffed(MonsterStatus.MAGIC_DEFENSE_UP), "single debuff preserves sibling");
        check(lastFlat(map.packets).entriesLength() == 1, "single debuff packet");
        mob.dispelSkill(new MobSkill(100, 1));
        check(!mob.isBuffed(MonsterStatus.MAGIC_DEFENSE_UP), "mob skill dispel");

        Runnable staleNewerExpiration = scheduler.actions.get(newer);
        MonsterStatusEffect replacement = effect(456789, Map.of(MonsterStatus.WATK, 4, MonsterStatus.STUN, 2));
        check(mob.applyStatus(null, replacement, false, 5000), "complete replacement");
        int beforeStale = map.packets.size();
        staleNewerExpiration.run();
        check(map.packets.size() == beforeStale && mob.getStati(MonsterStatus.STUN) == replacement,
                "stale interrupted expiration has no delivery");

        // Two concurrent variants must not both be accepted.
        MapleMonster raceMob = new MapleMonster(100100, stats);
        raceMob.setObjectId(124);
        raceMob.setMap(map);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Thread a = new Thread(() -> runRace(raceMob, start, accepted, Rogue.DISORDER));
        Thread b = new Thread(() -> runRace(raceMob, start, accepted, NightWalker.DISORDER));
        a.start(); b.start(); start.countDown(); a.join(); b.join();
        check(accepted.get() == 1, "atomic no refresh");
        MapleMonster boss = new MapleMonster(100100, stats);
        boss.setObjectId(125); boss.setMap(map); boss.setBoss(true);
        check(!boss.applyStatus(null, effect(Rogue.DISORDER, Map.of(MonsterStatus.WATK, -1)), false, 1000), "boss immunity");
        check(!boss.applyStatus(null, effect(NightWalker.DISORDER, Map.of(MonsterStatus.WATK, -1)), false, 1000), "variant boss immunity");

        // A spawn and its snapshot must precede an overlapping replacement's broadcasts.
        MapleMonster ordered = new MapleMonster(100100, stats);
        ordered.setObjectId(127); ordered.setMap(map);
        ordered.setParentMobOid(555); // production spawn also acquires map objectRLock
        check(ordered.applyStatus(null, effect(123, Map.of(MonsterStatus.SPEED, -10)), false, 5000), "ordered initial");
        BlockingSpawnClient waiting = new BlockingSpawnClient();
        map.recipient = waiting;
        map.exerciseMapLocks = true;
        CountDownLatch applied = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread spawn = new Thread(() -> {
            try { ordered.sendSpawnWithStatuses(waiting, () -> MaplePacketCreator.spawnMonster(ordered, false)); }
            catch (Throwable t) { failure.set(t); }
        });
        Thread replace = new Thread(() -> {
            try { ordered.applyStatus(null, effect(124, Map.of(MonsterStatus.SPEED, -20)), false, 5000); }
            catch (Throwable t) { failure.set(t); }
            finally { applied.countDown(); }
        });
        spawn.setDaemon(true); replace.setDaemon(true);
        spawn.start();
        check(waiting.entered.await(5, TimeUnit.SECONDS), "spawn entered");
        replace.start();
        try {
            check(!applied.await(100, TimeUnit.MILLISECONDS), "replacement waits for spawn and snapshot");
        } finally {
            waiting.release.countDown();
        }
        spawn.join(5000); replace.join(5000);
        check(!spawn.isAlive() && !replace.isAlive() && failure.get() == null, "ordered delivery completed");
        check(waiting.packets.size() == 4
                && ((waiting.packets.get(0)[0] & 255) | ((waiting.packets.get(0)[1] & 255) << 8))
                == SendOpcode.SPAWN_MONSTER.getValue(), "spawn/snapshot/legacy/flat order");
        check(entry(flat(waiting.packets.get(1)), MonsterStatus.SPEED).value() == -10
                && entry(flat(waiting.packets.get(3)), MonsterStatus.SPEED).value() == -20,
                "snapshot and replacement values ordered");
        map.recipient = null;

        // Check the production scheduler's own clock/interrupt, separately from the isolated lifecycle sink.
        int monitorPeriod = YamlConfig.config.server.MOB_STATUS_MONITOR_PROC;
        YamlConfig.config.server.MOB_STATUS_MONITOR_PROC = 10000;
        TimerManager.getInstance().start();
        MobStatusService real = new MobStatusService();
        try {
            Server.getInstance().forceUpdateCurrentTime();
            MonsterStatusEffect timed = effect(99999, Map.of(MonsterStatus.FREEZE, 1));
            AtomicInteger cancelled = new AtomicInteger();
            real.registerMobStatus(100000100, timed, cancelled::incrementAndGet, 100000);
            long first = real.remainingMobStatusMillis(100000100, timed);
            Server.getInstance().updateCurrentTime();
            long second = real.remainingMobStatusMillis(100000100, timed);
            check(first == 100000 && second < first && second >= 0, "real scheduler remaining clock");
            real.interruptMobStatus(100000100, timed);
            check(cancelled.get() == 1 && real.remainingMobStatusMillis(100000100, timed) == 0, "real scheduler interrupt");

            service.set(access, real);
            MapleMonster expiring = new MapleMonster(100100, stats);
            expiring.setObjectId(126); expiring.setMap(map);
            MonsterStatusEffect expiringEffect = effect(45678, Map.of(MonsterStatus.FREEZE, 1));
            check(expiring.applyStatus(null, expiringEffect, false, 1), "real scheduled apply");
            Server.getInstance().updateCurrentTime();
            Field schedulers = MobStatusService.class.getDeclaredField("mobStatusSchedulers");
            schedulers.setAccessible(true);
            Object[] schedulerArray = (Object[]) schedulers.get(real);
            int index = 100000100 / (1000000000 / YamlConfig.config.server.CHANNEL_LOCKS);
            Method tick = BaseScheduler.class.getDeclaredMethod("runBaseSchedule");
            tick.setAccessible(true);
            tick.invoke(schedulerArray[index]);
            check(!expiring.isBuffed(MonsterStatus.FREEZE)
                    && lastFlat(map.packets).operation() == MobStatusOperation.Cancel, "real scheduler expiry");

            MapleMonster scheduled = new MapleMonster(100100, stats);
            scheduled.setObjectId(128); scheduled.setMap(map);
            scheduled.setParentMobOid(555);
            check(scheduled.applyStatus(null, effect(123, Map.of(MonsterStatus.FREEZE, 1)), false, 1),
                    "scheduler overlap apply");
            Server.getInstance().updateCurrentTime();
            BlockingSpawnClient overlap = new BlockingSpawnClient();
            map.recipient = overlap;
            AtomicReference<Throwable> schedulerFailure = new AtomicReference<>();
            Thread spawning = new Thread(() -> {
                try { scheduled.sendSpawnWithStatuses(overlap, () -> MaplePacketCreator.spawnMonster(scheduled, false)); }
                catch (Throwable t) { schedulerFailure.set(t); }
            });
            CountDownLatch tickDone = new CountDownLatch(1);
            Thread ticking = new Thread(() -> {
                try { tick.invoke(schedulerArray[index]); }
                catch (Throwable t) { schedulerFailure.set(t); }
                finally { tickDone.countDown(); }
            });
            spawning.setDaemon(true); ticking.setDaemon(true);
            spawning.start();
            check(overlap.entered.await(5, TimeUnit.SECONDS), "scheduler overlap spawn entered");
            ticking.start();
            try {
                check(!tickDone.await(100, TimeUnit.MILLISECONDS), "expiry waits for spawn");
            } finally {
                overlap.release.countDown();
            }
            spawning.join(5000); ticking.join(5000);
            check(!spawning.isAlive() && !ticking.isAlive() && schedulerFailure.get() == null,
                    "scheduler and spawn lock ordering completes");
            check(overlap.packets.size() == 4 && flat(overlap.packets.get(1)).operation() == MobStatusOperation.Apply
                    && flat(overlap.packets.get(3)).operation() == MobStatusOperation.Cancel,
                    "expiry follows spawn snapshot");
            map.recipient = null;
        } finally {
            real.dispose();
            TimerManager.getInstance().stop();
            YamlConfig.config.server.MOB_STATUS_MONITOR_PROC = monitorPeriod;
        }
        System.out.println("PASS generic mob status lifecycle, snapshots, replacement, cancellation and Disorder");
    }
    static void runRace(MapleMonster mob, CountDownLatch start, AtomicInteger accepted, int id) {
        try {
            start.await();
            if (mob.applyStatus(null, effect(id, Map.of(MonsterStatus.WATK, -1)), false, 1000)) accepted.incrementAndGet();
        } catch (InterruptedException ex) { throw new AssertionError(ex); }
    }

    /** The older controller fixture assumes legacy-only packets. */
    public static class LegacyControllerRegression {
        public static void main(String[] args) throws Exception {
            YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
            Class.forName("MonsterControllerAssignmentTest").getMethod("main", String[].class)
                    .invoke(null, (Object) args);
        }
    }
}
