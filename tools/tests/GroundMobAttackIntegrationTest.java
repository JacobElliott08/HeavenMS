import client.MapleCharacter;
import client.MapleClient;
import client.MapleBuffStat;
import client.MapleJob;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.MapleInventoryType;
import client.status.MonsterStatus;
import config.YamlConfig;
import java.awt.Point;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import protocol.generated.maple.map.MobAttackEvent;
import protocol.generated.maple.map.MobAttackPhase;
import protocol.generated.maple.map.MobSnapshot;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.life.GroundMobAttackSimulation;
import server.life.GroundMobAttackProfile;
import server.life.GroundMobSimulation;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.maps.MapleFoothold;
import server.maps.MapleFootholdTree;
import server.maps.MapleMap;
import server.MapleStatEffect;
import net.server.channel.handlers.TakeDamageHandler;
import tools.data.output.MaplePacketLittleEndianWriter;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;
import provider.MapleDataProviderFactory;
import tools.MaplePacketCreator;

/** Offline actual tick/packet/damage path; only the second template is injected. */
public class GroundMobAttackIntegrationTest {
    private static void check(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static class TestMap extends MapleMap {
        TestMap() { super(100000100, 0, 1, 100000000, 1); }
        @Override public synchronized void startGroundMobSimulation() {}
        @Override public void moveMonster(MapleMonster mob, Point pos) { mob.setPosition(pos); }
        @Override public void killMonster(MapleMonster mob, MapleCharacter chr, boolean withDrops) {
            removeMapObject(mob);
            mob.disposeMapObject();
            broadcastMessage(MaplePacketCreator.killMonster(mob.getObjectId(), 1));
        }
    }

    private static class TestClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        Runnable impactHook;
        TestClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) {
            packets.add(packet);
            ByteBuffer bb = ByteBuffer.wrap(packet);
            if (impactHook != null && packet.length >= 8 && Packet.PacketBufferHasIdentifier(bb)) {
                Packet p = Packet.getRootAsPacket(bb);
                if (p.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobAttackEvent
                        && ((MobAttackEvent) p.serverMessage(new MobAttackEvent())).phase() == MobAttackPhase.Impact)
                    impactHook.run();
            }
        }
    }

    private static void concurrentDeathRegression() throws Exception {
        TestMap map = new TestMap();
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-500, -100), new Point(500, 100));
        tree.insert(new MapleFoothold(new Point(-500, 0), new Point(500, 0), 1));
        map.setFootholds(tree);
        TestClient client = new TestClient();
        MapleCharacter target = player(map, 70, 140, client);
        field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(target)));
        TestMob mob = new TestMob(3210800, MapleLifeFactory.getMonster(3210800).getStats());
        mob.setPosition(new Point(0, 0));
        mob.setFh(1);
        mob.setMap(map);
        map.addMapObject(mob);
        check(mob.hasServerGroundAttack(), "concurrency fixture attaches real Lupin authority");
        field(MapleMonster.class, "controller").set(mob, new WeakReference<>(target));
        field(MapleMonster.class, "controllerHasAggro").setBoolean(mob, true);
        CountDownLatch inImpact = new CountDownLatch(1), release = new CountDownLatch(1),
                competing = new CountDownLatch(1), deathDone = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.impactHook = () -> {
            inImpact.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("impact release timeout");
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        };
        Thread tick = new Thread(() -> {
            try { for (int i = 0; i < 15; ++i) mob.tickGroundAuthority(); }
            catch (Throwable t) { error.compareAndSet(null, t); }
        }, "ground-impact-test");
        Thread death = new Thread(() -> {
            try { competing.countDown(); mob.damage(target, mob.getHp(), false); }
            catch (Throwable t) { error.compareAndSet(null, t); }
            finally { deathDone.countDown(); }
        }, "ground-death-test");
        tick.setDaemon(true);
        death.setDaemon(true);
        tick.start();
        check(inImpact.await(5, TimeUnit.SECONDS), "tick reaches committed impact");
        death.start();
        check(competing.await(5, TimeUnit.SECONDS), "concurrent death started");
        check(!deathDone.await(100, TimeUnit.MILLISECONDS),
                "competing death waits for the impact's monster lock");
        release.countDown();
        tick.join(5000);
        death.join(5000);
        check(!tick.isAlive() && !death.isAlive() && error.get() == null,
                "impact and competing death finish without lock timeout: " + error.get());
        check(!mob.isAlive() && target.getHp() < 500 && receivedTerminalSnapshot(client),
                "concurrent death keeps committed hit and terminal state");
        check(events(client).stream().filter(e -> e.phase() == MobAttackPhase.Impact).count() == 1,
                "concurrent death publishes exactly one impact");
    }

    private static class TestMob extends MapleMonster {
        MonsterStatus blocked;
        boolean webbed;
        TestMob(int id, MapleMonsterStats stats) { super(id, stats); }
        @Override public boolean isBuffed(MonsterStatus status) {
            return status == blocked || super.isBuffed(status);
        }
        @Override public boolean isHermitShadowWebbed() { return webbed || super.isHermitShadowWebbed(); }
        @Override public boolean isCharacterPuppetInVicinity(MapleCharacter chr) { return false; }
    }

    private static MapleCharacter player(TestMap map, int id, int x, TestClient client) throws Exception {
        Method factory = MonsterControllerAssignmentTest.class.getDeclaredMethod("player",
                MapleMap.class, boolean.class, boolean.class, boolean.class, boolean.class);
        factory.setAccessible(true);
        MapleCharacter chr = (MapleCharacter) factory.invoke(null, map, true, false, false, true);
        field(MapleCharacter.class, "id").setInt(chr, id);
        field(MapleCharacter.class, "hp").setInt(chr, 500);
        field(MapleCharacter.class, "localmaxhp").setInt(chr, 500);
        field(MapleCharacter.class, "mp").setInt(chr, 300);
        field(MapleCharacter.class, "localmaxmp").setInt(chr, 300);
        field(MapleCharacter.class, "autoban").set(chr, new client.autoban.AutobanManager(chr));
        chr.setPosition(new Point(x, 0));
        chr.setMapTransitionComplete();
        chr.setClient(client);
        client.setPlayer(chr);
        return chr;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void magicGuard(MapleCharacter chr, int value) throws Exception {
        Class<?> holderType = Class.forName("client.MapleCharacter$MapleBuffStatValueHolder");
        Constructor<?> ctor = holderType.getDeclaredConstructor(MapleStatEffect.class, long.class, int.class);
        ctor.setAccessible(true);
        Map byStat = new EnumMap<>(MapleBuffStat.class);
        byStat.put(MapleBuffStat.MAGIC_GUARD, ctor.newInstance(new MapleStatEffect(), 1L, value));
        Map buffs = (Map) field(MapleCharacter.class, "buffEffects").get(chr);
        buffs.put(2001002, byStat);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void guaranteedReflection(MapleCharacter chr) throws Exception {
        Skill skill = SkillFactory.getSkill(2121002);
        if (skill == null) {
            Method load = SkillFactory.class.getDeclaredMethod("loadFromData", int.class, provider.MapleData.class);
            load.setAccessible(true);
            skill = (Skill) load.invoke(null, 2121002, MapleDataProviderFactory.getDataProvider(new File("wz/Skill.wz"))
                    .getData("212.img").getChildByPath("skill/2121002"));
            ((Map) field(SkillFactory.class, "skills").get(null)).put(2121002, skill);
        }
        MapleStatEffect effect = skill.getEffect(1);
        field(MapleStatEffect.class, "prop").setDouble(effect, 1.0);
        field(MapleStatEffect.class, "x").setInt(effect, 100);
        field(MapleCharacter.class, "job").set(chr, MapleJob.FP_ARCHMAGE);
        ((Map) field(MapleCharacter.class, "skills").get(chr))
                .put(skill, new MapleCharacter.SkillEntry((byte) 1, 1, -1));
        Class<?> holderType = Class.forName("client.MapleCharacter$MapleBuffStatValueHolder");
        Constructor<?> ctor = holderType.getDeclaredConstructor(MapleStatEffect.class, long.class, int.class);
        ctor.setAccessible(true);
        Object holder = ctor.newInstance(effect, 1L, 1);
        Map byStat = new EnumMap<>(MapleBuffStat.class);
        byStat.put(MapleBuffStat.MANA_REFLECTION, holder);
        ((Map) field(MapleCharacter.class, "buffEffects").get(chr)).put(skill.getId(), byStat);
        ((Map) field(MapleCharacter.class, "effects").get(chr)).put(MapleBuffStat.MANA_REFLECTION, holder);
    }

    private static void reflectionRegression() throws Exception {
        TestMap map = new TestMap();
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-500, -100), new Point(500, 100));
        tree.insert(new MapleFoothold(new Point(-500, 0), new Point(500, 0), 1));
        map.setFootholds(tree);
        TestClient targetClient = new TestClient(), observerClient = new TestClient();
        MapleCharacter target = player(map, 60, 140, targetClient);
        MapleCharacter observer = player(map, 61, 300, observerClient);
        guaranteedReflection(target);
        field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(target, observer)));
        TestMob mob = new TestMob(3210800, MapleLifeFactory.getMonster(3210800).getStats());
        mob.setPosition(new Point(0, 0));
        mob.setFh(1);
        mob.setMap(map);
        map.addMapObject(mob);
        check(mob.hasServerGroundAttack(), "reflection fixture attaches real Lupin authority");
        field(MapleMonster.class, "controller").set(mob, new WeakReference<>(target));
        field(MapleMonster.class, "controllerHasAggro").setBoolean(mob, true);
        ((AtomicInteger) field(MapleMonster.class, "hp").get(mob)).set(1);
        for (int i = 0; i < 15; i++) mob.tickGroundAuthority();
        check(!mob.isAlive() && target.getHp() < 500, "reflection kills attacker after actual target damage");
        for (TestClient client : new TestClient[]{targetClient, observerClient}) {
            int impact = -1, terminal = -1, impacts = 0;
            for (int i = 0; i < client.packets.size(); i++) {
                byte[] bytes = client.packets.get(i);
                ByteBuffer bb = ByteBuffer.wrap(bytes);
                if (bytes.length < 8 || !Packet.PacketBufferHasIdentifier(bb)) continue;
                Packet packet = Packet.getRootAsPacket(bb);
                if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobAttackEvent) {
                    MobAttackEvent event = (MobAttackEvent) packet.serverMessage(new MobAttackEvent());
                    if (event.phase() == MobAttackPhase.Impact) {
                        impact = i;
                        impacts++;
                        check(event.hit() && event.damage() == 500 - target.getHp(), "impact damage matches HP loss");
                    }
                } else if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobSnapshot) {
                    MobSnapshot snapshot = (MobSnapshot) packet.serverMessage(new MobSnapshot());
                    if (!snapshot.alive() && terminal < 0) terminal = i;
                }
            }
            check(impacts == 1 && impact >= 0 && terminal > impact,
                    "one impact must precede reflected attacker death, for target and observer");
        }
    }

    private static List<MobAttackEvent> events(TestClient client) {
        List<MobAttackEvent> events = new ArrayList<>();
        for (byte[] bytes : client.packets) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            if (bytes.length >= 8 && Packet.PacketBufferHasIdentifier(bb)) {
                Packet packet = Packet.getRootAsPacket(bb);
                if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobAttackEvent)
                    events.add((MobAttackEvent) packet.serverMessage(new MobAttackEvent()));
            }
        }
        return events;
    }

    private static void touch(TestClient client, MapleMonster mob, int damage) {
        MaplePacketLittleEndianWriter out = new MaplePacketLittleEndianWriter();
        out.writeInt(0);
        out.write(-1);
        out.write(0);
        out.writeInt(damage);
        out.writeInt(mob.getId());
        out.writeInt(mob.getObjectId());
        out.write(0);
        new TakeDamageHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                new ByteArrayByteStream(out.getPacket())), client);
    }

    private static boolean receivedTerminalSnapshot(TestClient client) {
        for (byte[] bytes : client.packets) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            if (bytes.length >= 8 && Packet.PacketBufferHasIdentifier(bb)) {
                Packet packet = Packet.getRootAsPacket(bb);
                if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobSnapshot) {
                    MobSnapshot snapshot = (MobSnapshot) packet.serverMessage(new MobSnapshot());
                    if (!snapshot.alive()) return true;
                }
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = true;
        Method isolate = MonsterControllerAssignmentTest.class.getDeclaredMethod("isolateDatabase");
        isolate.setAccessible(true);
        isolate.invoke(null);
        TestMap map = new TestMap();
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-500, -100), new Point(500, 100));
        tree.insert(new MapleFoothold(new Point(-500, 0), new Point(500, 0), 1));
        map.setFootholds(tree);
        TestClient targetClient = new TestClient(), observerClient = new TestClient();
        MapleCharacter target = player(map, 42, 140, targetClient);
        MapleCharacter observer = player(map, 43, 300, observerClient);
        Equip armor = new Equip(1040002, (short) -5);
        armor.setWdef((short) 100);
        target.getInventory(MapleInventoryType.EQUIPPED).addItemFromDB(armor);
        magicGuard(target, 50);
        field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(target, observer)));
        TestMob mob = new TestMob(3210800, MapleLifeFactory.getMonster(3210800).getStats());
        mob.setPosition(new Point(0, 0));
        mob.setFh(1);
        mob.setMap(map);
        check(mob.hasServerGroundAttack(), "production Lupin attaches movement and attack together");
        map.addMapObject(mob);
        field(MapleMonster.class, "controller").set(mob, new WeakReference<>(target));
        field(MapleMonster.class, "controllerHasAggro").setBoolean(mob, true);
        for (int i = 0; i < 15; ++i) mob.tickGroundAuthority();
        List<MobAttackEvent> targetEvents = events(targetClient), observerEvents = events(observerClient);
        check(targetEvents.size() == 3 && observerEvents.size() == 3, "start, launch, impact reach target and observer");
        check(targetEvents.get(0).phase() == MobAttackPhase.Start
                && targetEvents.get(1).phase() == MobAttackPhase.Launch
                && targetEvents.get(2).phase() == MobAttackPhase.Impact, "event order");
        int damage = targetEvents.get(2).damage();
        int mpLoss = damage / 2;
        check(targetEvents.get(2).hit() && damage >= 50 && damage <= 63
                && target.getHp() == 500 - (damage - mpLoss) && target.getMp() == 300 - mpLoss,
                "WDEF roll and Magic Guard split are applied exactly once");
        check(observerEvents.get(2).damage() == damage && observerEvents.get(2).attackId() == targetEvents.get(2).attackId(),
                "observer sees same authoritative impact");
        touch(targetClient, mob, 10);
        check(target.getHp() == 500 - (damage - mpLoss) && target.getMp() == 300 - mpLoss,
                "legacy touch immediately after server impact is suppressed");
        check(new TakeDamageHandler().applyServerGroundImpact(target, mob, 10, 0) == -1,
                "duplicate server impact cannot mutate during invulnerability");
        for (int i = 0; i < 10; ++i) mob.tickGroundAuthority();
        check(target.getHp() == 500 - (damage - mpLoss) && target.getMp() == 300 - mpLoss,
                "no duplicate HP/MP mutation during recovery");

        targetClient.packets.clear();
        observerClient.packets.clear();
        field(MapleMonster.class, "groundAttack").set(mob, new GroundMobAttackSimulation(mob.getStats().groundAttackProfile));
        mob.tickGroundAuthority();
        for (int i = 0; i < 4; ++i) mob.tickGroundAuthority();
        mob.blocked = MonsterStatus.STUN;
        mob.tickGroundAuthority();
        for (int i = 0; i < 20; ++i) mob.tickGroundAuthority();
        List<MobAttackEvent> interrupted = events(targetClient);
        check(interrupted.size() == 2 && interrupted.get(0).phase() == MobAttackPhase.Start
                && interrupted.get(1).phase() == MobAttackPhase.Cancel,
                "stun cancels windup without launch or impact");
        check(target.getHp() == 500 - (damage - mpLoss), "stun does not mutate HP");
        mob.blocked = null;
        for (MonsterStatus status : new MonsterStatus[]{MonsterStatus.FREEZE, MonsterStatus.NEUTRALISE}) {
            targetClient.packets.clear();
            field(MapleMonster.class, "groundAttack").set(mob, new GroundMobAttackSimulation(mob.getStats().groundAttackProfile));
            mob.tickGroundAuthority();
            mob.blocked = status;
            mob.tickGroundAuthority();
            check(events(targetClient).size() == 2 && events(targetClient).get(1).phase() == MobAttackPhase.Cancel,
                    status + " cancels the real tick");
            mob.blocked = null;
        }
        targetClient.packets.clear();
        field(MapleMonster.class, "groundAttack").set(mob, new GroundMobAttackSimulation(mob.getStats().groundAttackProfile));
        mob.tickGroundAuthority();
        mob.webbed = true;
        mob.tickGroundAuthority();
        check(events(targetClient).size() == 2 && events(targetClient).get(1).phase() == MobAttackPhase.Cancel,
                "web cancels the real tick");
        mob.webbed = false;
        targetClient.packets.clear();
        observerClient.packets.clear();
        observer.setPosition(new Point(100, 0));
        field(MapleMonster.class, "groundAttack").set(mob, new GroundMobAttackSimulation(mob.getStats().groundAttackProfile));
        mob.tickGroundAuthority();
        for (int i = 0; i < 4; ++i) mob.tickGroundAuthority();
        field(MapleMonster.class, "controller").set(mob, new WeakReference<>(observer));
        mob.tickGroundAuthority();
        for (int i = 0; i < 14; ++i) mob.tickGroundAuthority();
        List<MobAttackEvent> switched = events(targetClient);
        check(switched.size() == 5 && switched.get(0).phase() == MobAttackPhase.Start
                && switched.get(1).phase() == MobAttackPhase.Cancel
                && switched.get(2).phase() == MobAttackPhase.Start
                && switched.get(2).targetId() == 43
                && switched.get(4).phase() == MobAttackPhase.Impact
                && switched.get(4).targetId() == 43 && observer.getHp() < 500,
                "aggro switch cancels old windup and damages only new target");
        check(target.getHp() == 500 - (damage - mpLoss), "old target takes no switched attack damage");
        TestClient thirdClient = new TestClient();
        MapleCharacter third = player(map, 44, 80, thirdClient);
        touch(thirdClient, mob, 10);
        check(third.getHp() == 490, "touch remains legacy before a ground impact");
        check(new TakeDamageHandler().applyServerGroundImpact(third, mob, 10, 0) == -1
                && third.getHp() == 490, "touch protects against overlapping server impact");
        field(MapleMonster.class, "controller").set(mob, new WeakReference<>(target));
        targetClient.packets.clear();
        observerClient.packets.clear();
        field(MapleMonster.class, "groundAttack").set(mob, new GroundMobAttackSimulation(mob.getStats().groundAttackProfile));
        mob.tickGroundAuthority();
        int beforeKnockback = mob.getPosition().x;
        mob.damage(third, 1, false);
        mob.tickGroundAuthority();
        check(events(targetClient).size() == 2 && events(targetClient).get(1).phase() == MobAttackPhase.Cancel
                && mob.getPosition().x < beforeKnockback,
                "accepted hit cancels windup without erasing knockback");
        for (int i = 0; i < 4; ++i) mob.tickGroundAuthority();
        targetClient.packets.clear();
        observerClient.packets.clear();
        field(MapleMonster.class, "groundAttack").set(mob, new GroundMobAttackSimulation(mob.getStats().groundAttackProfile));
        mob.tickGroundAuthority();
        mob.damage(third, mob.getHp(), false);
        for (int i = 0; i < 20; ++i) mob.tickGroundAuthority();
        check(events(targetClient).size() == 1 && target.getHp() == 500 - (damage - mpLoss),
                "death prevents late launch and impact");
        check(receivedTerminalSnapshot(targetClient) && receivedTerminalSnapshot(observerClient),
                "real death sends terminal snapshot to both observers");
        TestMob zombie = new TestMob(4230101, MapleLifeFactory.getMonster(4230101).getStats());
        zombie.setPosition(new Point(0, 0));
        zombie.setFh(1);
        zombie.setMap(map);
        check(!zombie.isServerGroundAuthority(), "Zombie Lupin remains production legacy");
        map.addMapObject(zombie);
        GroundMobAttackProfile zombieProfile = GroundMobAttackProfile.fromWz(
                MapleDataProviderFactory.getDataProvider(new File("wz/Mob.wz"))
                        .getData("4230101.img"), 5);
        check(zombieProfile != null && zombieProfile.physicalAttack() == 135, "real Zombie Lupin WZ profile");
        zombie.getStats().groundAttackProfile = zombieProfile;
        GroundMobSimulation zombieMovement = GroundMobSimulation.create(1210100,
                MapleLifeFactory.getMonster(1210100).getStats(), tree, 1, new Point(0, 0), false);
        field(MapleMonster.class, "groundSimulation").set(zombie, zombieMovement);
        field(MapleMonster.class, "groundAttack").set(zombie, new GroundMobAttackSimulation(zombieProfile));
        TestClient zombieTargetClient = new TestClient();
        MapleCharacter zombieTarget = player(map, 45, 140, zombieTargetClient);
        field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(zombieTarget)));
        field(MapleMonster.class, "controller").set(zombie, new WeakReference<>(zombieTarget));
        field(MapleMonster.class, "controllerHasAggro").setBoolean(zombie, true);
        for (int i = 0; i < 15; ++i) zombie.tickGroundAuthority();
        List<MobAttackEvent> zombieEvents = events(zombieTargetClient);
        check(zombieEvents.size() == 3 && zombieEvents.get(2).phase() == MobAttackPhase.Impact
                && zombieEvents.get(2).damage() >= 108 && zombieEvents.get(2).damage() < 135
                && zombieTarget.getHp() == 500 - zombieEvents.get(2).damage(),
                "same real tick and damage adapter consume a second WZ profile without production opt-in");
        reflectionRegression();
        concurrentDeathRegression();
        System.out.println("PASS production Lupin tick, observers, mitigation, interruption, reflection ordering and concurrent death; injected Zombie Lupin reuse");
    }
}
