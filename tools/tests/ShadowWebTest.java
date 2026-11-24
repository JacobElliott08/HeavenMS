import client.MapleCharacter;
import client.MapleJob;
import client.Skill;
import client.SkillFactory;
import client.status.MonsterStatus;
import client.status.MonsterStatusEffect;
import config.YamlConfig;
import constants.skills.Hermit;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.server.channel.Channel;
import net.server.channel.handlers.MoveLifeHandler;
import net.server.channel.handlers.SpecialMoveHandler;
import net.server.services.Service;
import net.server.services.ServicesManager;
import net.server.services.BaseScheduler;
import net.server.services.task.channel.MobStatusService;
import net.server.services.type.ChannelServices;
import server.MapleStatEffect;
import server.TimerManager;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.life.MonsterListener;
import server.maps.MapleFoothold;
import server.maps.MapleFootholdTree;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;

/** Offline production WZ, SPECIAL_MOVE, status scheduler and movement fixture. */
public class ShadowWebTest {
    static final class Scheduler extends MobStatusService {
        final Map<MonsterStatusEffect, Runnable> actions = new IdentityHashMap<>();
        final Map<MonsterStatusEffect, Runnable> ticks = new IdentityHashMap<>();
        final Map<MonsterStatusEffect, Long> remaining = new IdentityHashMap<>();
        final Map<MonsterStatusEffect, Integer> delays = new IdentityHashMap<>();
        @Override public void registerMobStatus(int map, MonsterStatusEffect effect, Runnable cancel,
                                                long duration, Runnable overtime, int delay) {
            actions.put(effect, cancel);
            remaining.put(effect, duration);
            if (overtime != null) { ticks.put(effect, overtime); delays.put(effect, delay); }
        }
        @Override public void interruptMobStatus(int map, MonsterStatusEffect effect) {
            Runnable action = actions.remove(effect);
            remaining.remove(effect); ticks.remove(effect); delays.remove(effect);
            if (action != null) action.run();
        }
        @Override public long remainingMobStatusMillis(int map, MonsterStatusEffect effect) {
            return remaining.getOrDefault(effect, 0L);
        }
    }
    static final Scheduler scheduler = new Scheduler();
    static class TestMap extends MesoUpTest.MapFixture {
        Channel channel;
        final List<byte[]> packets = new ArrayList<>();
        @Override public Channel getChannelServer() { return channel; }
        @Override public void broadcastMessage(byte[] bytes, Point position) { packets.add(bytes); }
        @Override public void broadcastMessage(byte[] bytes) { packets.add(bytes); }
        @Override public void broadcastMessage(MapleCharacter chr, byte[] bytes, boolean repeat) { packets.add(bytes); }
        @Override public synchronized void startGroundMobSimulation() {}
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void initChannel(TestMap map) throws Exception {
        initChannel(map, scheduler);
    }
    static void initChannel(TestMap map, MobStatusService statusService) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        map.channel = (Channel) unsafe.allocateInstance(Channel.class);
        ServicesManager services = (ServicesManager) unsafe.allocateInstance(ServicesManager.class);
        Service<?> access = (Service<?>) unsafe.allocateInstance(Service.class);
        MesoUpTest.field(Service.class, "cls").set(access, MobStatusService.class);
        MesoUpTest.field(Service.class, "service").set(access, statusService);
        Service<?>[] entries = new Service<?>[ChannelServices.values().length];
        entries[ChannelServices.MOB_STATUS.ordinal()] = access;
        MesoUpTest.field(ServicesManager.class, "services").set(services, entries);
        MesoUpTest.field(Channel.class, "services").set(map.channel, services);
    }
    static void cast(MapleCharacter player, int level) {
        ByteBuffer b = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).putInt(Hermit.SHADOW_WEB).put((byte) level);
        new SpecialMoveHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                new ByteArrayByteStream(b.array())), player.getClient());
    }
    static MapleMonster mob(TestMap map, int x, int y, boolean boss) {
        MapleMonsterStats stats = new MapleMonsterStats();
        stats.setHp(1000);
        stats.setBoss(boss);
        MapleMonster mob = new MapleMonster(100100, stats);
        mob.setPosition(new Point(x, y));
        mob.setMap(map);
        map.addMapObject(mob);
        return mob;
    }
    static void movement(MapleCharacter player, MapleMonster mob, int x) {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(mob.getObjectId()).putShort((short) 1);
        b.put((byte) 1).put((byte) -1).put((byte) 0).put((byte) 0).putShort((short) 0);
        b.putLong(0).put((byte) 0).putInt(0).putShort((short) 0).putShort((short) 2);
        b.put((byte) 1).put((byte) 0).putShort((short) x).putShort((short) 2);
        b.putShort((short) 0).putShort((short) 0).putShort((short) 1).put((byte) 2).putShort((short) 1);
        new MoveLifeHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                new ByteArrayByteStream(b.array())), player.getClient());
    }
    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
        Method isolation = ClawAttackTransportTest.class.getDeclaredMethod("isolateDatabase");
        isolation.setAccessible(true); isolation.invoke(null);
        SkillFactory.loadAllSkills();
        Skill skill = SkillFactory.getSkill(Hermit.SHADOW_WEB);
        check(skill != null && skill.getMaxLevel() == 20, "WZ skill");
        Method bounds = MapleStatEffect.class.getDeclaredMethod("calculateBoundingBox", Point.class, boolean.class);
        bounds.setAccessible(true);
        for (int level = 1; level <= 20; level++) {
            MapleStatEffect e = skill.getEffect(level);
            check(e.getDuration() == (5 + (level - 1) / 5) * 1000, "duration " + level);
            check(e.getMpCon() == 10 + 4 * ((level - 1) / 5), "MP " + level);
            check(Math.abs(e.getProp() - (40 + 2 * level) / 100.0) < 0.00001, "chance " + level);
            check(e.getMobCount() == 6 && e.getMonsterStati().containsKey(MonsterStatus.SHADOW_WEB), "status " + level);
            int range = level <= 10 ? 200 : 250;
            Rectangle left = (Rectangle) bounds.invoke(e, new Point(0, 0), true);
            Rectangle right = (Rectangle) bounds.invoke(e, new Point(0, 0), false);
            check(left.x == -range && right.x == 0 && left.width == range && right.width == range
                    && left.y == -120 && right.y == -120 && left.height == 120,
                    "facing range " + level);
        }
        TestMap map = new TestMap(); initChannel(map);
        MapleCharacter player = MesoUpTest.player(map, 1, 500, 0);
        player.setMapTransitionComplete();
        MesoUpTest.learn(player, skill, 20);
        MapleStatEffect effect = skill.getEffect(20);
        Field prop = MesoUpTest.field(MapleStatEffect.class, "prop");
        prop.setDouble(effect, 1.0); // deterministic hit; production still rolls per target.
        player.setStance(5); // left
        MapleMonster inside = mob(map, -250, 0, false);
        AtomicInteger listenerDamage = new AtomicInteger();
        inside.addListener(new MonsterListener() {
            public void monsterKilled(int animationTime) {}
            public void monsterDamaged(MapleCharacter from, int damage) { listenerDamage.addAndGet(damage); }
            public void monsterHealed(int heal) {}
        });
        MapleMonster wrongSide = mob(map, 10, 0, false);
        MapleMonster outside = mob(map, -251, 0, false);
        MapleMonster boss = mob(map, -30, -10, true);
        cast(player, 19);
        check(player.getMp() == 500 && !inside.isBuffed(MonsterStatus.SHADOW_WEB), "level mismatch");
        MesoUpTest.field(MapleCharacter.class, "job").set(player, MapleJob.BANDIT);
        cast(player, 20);
        check(player.getMp() == 500 && !inside.isBuffed(MonsterStatus.SHADOW_WEB), "job rejected");
        MesoUpTest.field(MapleCharacter.class, "job").set(player, MapleJob.HERMIT);
        cast(player, 20);
        check(player.getMp() == 478 && inside.isBuffed(MonsterStatus.SHADOW_WEB), "left edge and cost");
        check(inside.getHp() == 1000, "web does not deal direct damage");
        check(!wrongSide.isBuffed(MonsterStatus.SHADOW_WEB) && !outside.isBuffed(MonsterStatus.SHADOW_WEB)
                && !boss.isBuffed(MonsterStatus.SHADOW_WEB), "range and boss immunity");
        MonsterStatusEffect old = inside.getStati(MonsterStatus.SHADOW_WEB);
        check(scheduler.remaining.get(old) == 8000, "exact duration");
        check(scheduler.ticks.containsKey(old) && scheduler.delays.get(old) == 3500,
                "historical 3.5s web damage registered");
        check(old.getStati().get(MonsterStatus.SHADOW_WEB) == 20, "two-percent status value");
        int packetCount = map.packets.size();
        Runnable oldTick = scheduler.ticks.get(old);
        oldTick.run();
        check(inside.getHp() == 980, "server tick changes HP by two percent");
        byte[] damagePacket = map.packets.get(map.packets.size() - 1);
        ByteBuffer wire = ByteBuffer.wrap(damagePacket).order(ByteOrder.LITTLE_ENDIAN);
        check(map.packets.size() > packetCount && Short.toUnsignedInt(wire.getShort()) ==
                net.opcodes.SendOpcode.DAMAGE_MONSTER.getValue() && wire.getInt() == inside.getObjectId()
                && wire.get() == 0 && wire.getInt() == 20, "broadcast damage equals HP loss");
        oldTick.run();
        check(inside.getHp() == 960, "successive ticks use maximum HP");
        @SuppressWarnings("unchecked")
        Map<Integer, AtomicLong> credit = (Map<Integer, AtomicLong>) MesoUpTest.field(MapleMonster.class, "takenDamage").get(inside);
        check(listenerDamage.get() == 40 && credit.get(player.getId()).get() == 40,
                "ticks retain damage listeners and caster credit");
        check(MobStatusLifecycleTest.entry(MobStatusLifecycleTest.lastFlat(map.packets),
                MonsterStatus.SHADOW_WEB).sourceSkillId() == Hermit.SHADOW_WEB, "MSFB source");
        Runnable stale = scheduler.actions.get(old);
        cast(player, 20);
        MonsterStatusEffect replacement = inside.getStati(MonsterStatus.SHADOW_WEB);
        check(old != replacement, "refresh");
        stale.run();
        check(inside.getStati(MonsterStatus.SHADOW_WEB) == replacement, "stale expiry");
        oldTick.run();
        check(inside.getHp() == 960, "replaced tick cannot damage");
        MesoUpTest.field(MapleMonster.class, "controller").set(inside, new WeakReference<>(player));
        Point before = new Point(inside.getPosition());
        movement(player, inside, 100);
        check(inside.getPosition().equals(before), "legacy movement blocked");
        scheduler.actions.get(replacement).run();
        check(!inside.isBuffed(MonsterStatus.SHADOW_WEB), "expiry");
        scheduler.ticks.get(replacement).run();
        check(inside.getHp() == 960, "expired tick cannot damage");
        movement(player, inside, 100);
        check(inside.getPosition().x == 100, "legacy movement resumes");
        prop.setDouble(effect, 0.0);
        player.setStance(4); // right
        cast(player, 20);
        check(!wrongSide.isBuffed(MonsterStatus.SHADOW_WEB), "failed chance");
        prop.setDouble(effect, 1.0);
        cast(player, 20);
        check(wrongSide.isBuffed(MonsterStatus.SHADOW_WEB), "right-facing range");

        // The legacy status packet carries a signed short: large webs are capped
        // consistently in the status, actual HP loss, and displayed packet.
        MapleMonsterStats largeStats = new MapleMonsterStats();
        largeStats.setHp(2000000);
        MapleMonster large = new MapleMonster(100100, largeStats);
        large.setPosition(new Point(-30, 0)); large.setMap(map); map.addMapObject(large);
        MonsterStatusEffect largeWeb = new MonsterStatusEffect(effect.getMonsterStati(), skill, null, false);
        check(large.applyStatus(player, largeWeb, false, 8000), "large mob web");
        check(largeWeb.getStati().get(MonsterStatus.SHADOW_WEB) == Short.MAX_VALUE, "wire-safe damage cap");
        Runnable largeTick = scheduler.ticks.get(largeWeb);
        largeTick.run();
        check(large.getHp() == 2000000 - Short.MAX_VALUE, "capped actual damage");
        ByteBuffer largeWire = ByteBuffer.wrap(map.packets.get(map.packets.size() - 1)).order(ByteOrder.LITTLE_ENDIAN);
        check(Short.toUnsignedInt(largeWire.getShort()) == net.opcodes.SendOpcode.DAMAGE_MONSTER.getValue()
                && largeWire.getInt() == large.getObjectId() && largeWire.get() == 0
                && largeWire.getInt() == Short.MAX_VALUE, "capped damage packet");
        scheduler.remaining.put(largeWeb, 0L);
        int beforeExpiry = large.getHp();
        largeTick.run();
        check(large.getHp() == beforeExpiry, "queued callback after deadline does not damage");
        scheduler.remaining.put(largeWeb, 8000L);
        large.applyAndGetHpDamage(large.getHp() - 5, false);
        int terminalStart = map.packets.size();
        largeTick.run();
        check(large.getHp() == 1 && !large.isBuffed(MonsterStatus.SHADOW_WEB), "nonlethal terminal tick cancels web");
        int damageIndex = -1, cancelIndex = -1;
        for (int i = terminalStart; i < map.packets.size(); i++) {
            byte[] p = map.packets.get(i);
            if (p.length < 2) continue;
            ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            int opcode = Short.toUnsignedInt(b.getShort());
            if (opcode == net.opcodes.SendOpcode.DAMAGE_MONSTER.getValue()) {
                damageIndex = i;
                check(b.getInt() == large.getObjectId() && b.get() == 0 && b.getInt() == 4,
                        "terminal packet is actual loss");
            } else if (opcode == net.opcodes.SendOpcode.CANCEL_MONSTER_STATUS.getValue()) {
                cancelIndex = i;
            }
        }
        check(damageIndex >= 0 && cancelIndex > damageIndex, "terminal damage precedes status cancellation");
        largeTick.run();
        check(large.getHp() == 1, "interrupted callback does not damage");
        MapleMonster removed = mob(map, -30, 0, false);
        MonsterStatusEffect removedWeb = new MonsterStatusEffect(effect.getMonsterStati(), skill, null, false);
        check(removed.applyStatus(player, removedWeb, false, 8000), "removed mob web");
        Runnable removedTick = scheduler.ticks.get(removedWeb);
        Field playerMap = MesoUpTest.field(MapleCharacter.class, "map");
        playerMap.set(player, new TestMap());
        removedTick.run();
        check(removed.getHp() == 1000, "queued tick does not damage after caster changes map");
        playerMap.set(player, map);
        map.removeMapObject(removed);
        removedTick.run();
        check(removed.getHp() == 1000, "queued tick does not damage removed map object");

        TestMap capped = new TestMap(); initChannel(capped);
        MapleCharacter cappedPlayer = MesoUpTest.player(capped, 2, 0, 0);
        cappedPlayer.setStance(5);
        MesoUpTest.learn(cappedPlayer, skill, 20);
        List<MapleMonster> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) candidates.add(mob(capped, -10 - i, 0, false));
        cast(cappedPlayer, 20);
        check(candidates.stream().noneMatch(m -> m.isBuffed(MonsterStatus.SHADOW_WEB)), "insufficient MP");
        MesoUpTest.field(MapleCharacter.class, "mp").setInt(cappedPlayer, 500);
        MesoUpTest.field(MapleCharacter.class, "job").set(cappedPlayer, MapleJob.NIGHTLORD);
        cast(cappedPlayer, 20);
        check(candidates.stream().filter(m -> m.isBuffed(MonsterStatus.SHADOW_WEB)).count() == 6,
                "night lord eligibility and six-target cap");

        TestMap ground = new TestMap(); initChannel(ground);
        MapleFoothold f = new MapleFoothold(new Point(-1000, 0), new Point(1000, 0), 1);
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(f); ground.setFootholds(tree);
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = true;
        MapleMonster snail = MapleLifeFactory.getMonster(100100);
        snail.setFh(1); snail.setPosition(new Point(0, 0)); snail.setMap(ground); ground.addMapObject(snail);
        check(snail.isServerGroundAuthority(), "ground fixture");
        MonsterStatusEffect web = new MonsterStatusEffect(effect.getMonsterStati(), skill, null, false);
        check(snail.applyStatus(player, web, false, 8000), "ground status");
        Point frozen = new Point(snail.getPosition());
        for (int i = 0; i < 10; i++) snail.tickGroundAuthority();
        check(snail.getPosition().equals(frozen), "server ground immobilized");
        scheduler.actions.get(web).run();
        boolean moved = false;
        for (int i = 0; i < 40; i++) {
            snail.tickGroundAuthority();
            moved |= !snail.getPosition().equals(frozen);
        }
        check(moved, "server ground resumes");

        TestMap racing = new TestMap(); initChannel(racing);
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
        MapleCharacter racingPlayer = MesoUpTest.player(racing, 3, 500, 0);
        MapleMonster raceMob = mob(racing, -20, 0, false);
        MonsterStatusEffect raceWeb = new MonsterStatusEffect(effect.getMonsterStati(), skill, null, false);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean applied = new AtomicBoolean();
        raceMob.lockMonster();
        Thread apply = new Thread(() -> {
            started.countDown();
            applied.set(raceMob.applyStatus(racingPlayer, raceWeb, false, 8000));
        });
        try {
            apply.start();
            check(started.await(2, TimeUnit.SECONDS), "application thread started");
            apply.join(100);
            check(apply.isAlive() && !raceMob.isHermitShadowWebbed(), "application waits for movement lock");
        } finally {
            raceMob.unlockMonster();
        }
        apply.join(2000);
        check(!apply.isAlive() && applied.get() && raceMob.isHermitShadowWebbed(), "serialized application");
        Runnable deadTick = scheduler.ticks.get(raceWeb);
        raceMob.setHpZero();
        deadTick.run();
        check(raceMob.getHp() == 0, "dead mob receives no tick");
        scheduler.actions.get(raceWeb).run();
        check(!raceMob.isHermitShadowWebbed(), "dead mob status cleanup");

        // Drive the real scheduler deterministically at its configured 200 ms
        // resolution. Cancel only this fixture's timer, not any user process.
        int monitor = YamlConfig.config.server.MOB_STATUS_MONITOR_PROC;
        YamlConfig.config.server.MOB_STATUS_MONITOR_PROC = 200;
        TimerManager.getInstance().start();
        MobStatusService real = new MobStatusService();
        try {
            net.server.Server.getInstance().forceUpdateCurrentTime();
            TestMap timedMap = new TestMap(); initChannel(timedMap, real);
            MapleCharacter timedPlayer = MesoUpTest.player(timedMap, 4, 500, 0);
            MapleMonster timedMob = mob(timedMap, -10, 0, false);
            MonsterStatusEffect timedWeb = new MonsterStatusEffect(effect.getMonsterStati(), skill, null, false);
            check(timedMob.applyStatus(timedPlayer, timedWeb, false, 8000), "real scheduler apply");
            Object[] schedulerArray = (Object[]) MesoUpTest.field(MobStatusService.class, "mobStatusSchedulers").get(real);
            int index = timedMap.getId() / (1000000000 / YamlConfig.config.server.CHANNEL_LOCKS);
            Object realScheduler = schedulerArray[index];
            java.util.concurrent.ScheduledFuture<?> future = (java.util.concurrent.ScheduledFuture<?>)
                    MesoUpTest.field(BaseScheduler.class, "schedulerTask").get(realScheduler);
            future.cancel(false);
            Method monitorTick = BaseScheduler.class.getDeclaredMethod("runBaseSchedule");
            monitorTick.setAccessible(true);
            for (int i = 0; i < 17; i++) monitorTick.invoke(realScheduler);
            check(timedMob.getHp() == 1000, "no tick before scheduler rounds 3500 ms up");
            monitorTick.invoke(realScheduler);
            check(timedMob.getHp() == 980, "real scheduler executes web damage on eighteenth 200 ms cycle");
            real.interruptMobStatus(timedMap.getId(), timedWeb);
            for (int i = 0; i < 18; i++) monitorTick.invoke(realScheduler);
            check(timedMob.getHp() == 980, "real scheduler removes overtime on cancellation");
        } finally {
            real.dispose();
            TimerManager.getInstance().stop();
            YamlConfig.config.server.MOB_STATUS_MONITOR_PROC = monitor;
        }
        prop.setDouble(effect, 0.8);
        System.out.println("ShadowWebTest passed");
    }
}
