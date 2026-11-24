import client.MapleCharacter;
import client.MapleClient;
import client.MapleJob;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.MapleInventoryType;
import com.google.flatbuffers.FlatBufferBuilder;
import config.YamlConfig;
import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.server.channel.handlers.FlatBufferAttackHandler;
import net.server.channel.handlers.PlayerMapTransitionHandler;
import net.server.channel.handlers.CloseRangeDamageHandler;
import net.server.audit.locks.MonitoredReentrantLock;
import protocol.generated.maple.combat.AttackRequest;
import protocol.generated.maple.map.MobAttackEvent;
import protocol.generated.maple.map.MobAttackPhase;
import protocol.generated.maple.network.ClientMessage;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.TimerManager;
import server.maps.MapleFoothold;
import server.maps.MapleFootholdTree;
import server.maps.MapleMap;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;

/** Real request -> damage -> coordinator/controller -> authority tick, offline. */
public class LupinPlayerHitTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static class TestMap extends MapleMap {
        boolean scheduled;
        TestMap() { super(100000100, 0, 1, 100000000, 1); }
        @Override public synchronized void startGroundMobSimulation() {
            if (scheduled) super.startGroundMobSimulation();
        }
    }

    private static class TestClient extends MapleClient {
        final List<byte[]> packets = new CopyOnWriteArrayList<>();
        TestClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) { packets.add(packet); }
    }

    private static class TestMob extends MapleMonster {
        TestMob() { super(3210800, MapleLifeFactory.getMonster(3210800).getStats()); }
        @Override public void aggroUpdatePuppetVisibility() {
            // The unrelated channel OverallService is not available offline.
        }
    }

    private static AttackRequest request(int oid) {
        FlatBufferBuilder b = new FlatBufferBuilder(128);
        int targets = AttackRequest.createTargetOidsVector(b, new int[]{oid});
        int body = AttackRequest.createAttackRequest(b, 1, 0, 0, 23, false, 6, 0,
                (short) 0, (short) 0, targets);
        Packet.startPacket(b);
        Packet.addClientMessageType(b, ClientMessage.protocol_generated_maple_combat_AttackRequest);
        Packet.addClientMessage(b, body);
        Packet.finishPacketBuffer(b, Packet.endPacket(b));
        ByteBuffer bytes = ByteBuffer.wrap(b.sizedByteArray());
        check(Packet.PacketBufferHasIdentifier(bytes), "request uses MSFB");
        return (AttackRequest) Packet.getRootAsPacket(bytes).clientMessage(new AttackRequest());
    }

    private static void legacyHit(int oid, MapleClient client) {
        ByteBuffer bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 0).put((byte) 0x11).putInt(0);
        bytes.position(bytes.position() + 8);
        bytes.put((byte) 0).put((byte) 0).put((byte) 0);
        bytes.put((byte) 0).put((byte) 6);
        bytes.position(bytes.position() + 4);
        bytes.putInt(oid);
        bytes.position(bytes.position() + 14);
        bytes.putInt(20);
        new CloseRangeDamageHandler().handlePacket(
                new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(bytes.array())), client);
    }

    private static boolean hasDamagingImpact(TestClient client) {
        for (byte[] bytes : client.packets) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            if (bytes.length < 8 || !Packet.PacketBufferHasIdentifier(bb)) continue;
            Packet packet = Packet.getRootAsPacket(bb);
            if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobAttackEvent) {
                MobAttackEvent event = (MobAttackEvent) packet.serverMessage(new MobAttackEvent());
                if (event.phase() == MobAttackPhase.Impact && event.hit() && event.damage() > 0) return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        boolean fallback = Arrays.asList(args).contains("fallback");
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = !fallback;
        boolean trace = Arrays.asList(args).contains("trace");
        YamlConfig.config.server.USE_LUPIN_ATTACK_TRACE = trace;
        Method isolate = MonsterControllerAssignmentTest.class.getDeclaredMethod("isolateDatabase");
        isolate.setAccessible(true);
        isolate.invoke(null);
        TestMap map = new TestMap();
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-1000, -100), new Point(1000, 100));
        tree.insert(new MapleFoothold(new Point(-1000, 0), new Point(1000, 0), 1));
        map.setFootholds(tree);
        Method factory = MonsterControllerAssignmentTest.class.getDeclaredMethod("player",
                MapleMap.class, boolean.class, boolean.class, boolean.class, boolean.class);
        factory.setAccessible(true);
        MapleCharacter chr = (MapleCharacter) factory.invoke(null, map, true, false, false, true);
        field(MapleCharacter.class, "id").setInt(chr, 91);
        field(MapleCharacter.class, "level").setInt(chr, 50);
        field(MapleCharacter.class, "job").set(chr, MapleJob.ASSASSIN);
        field(MapleCharacter.class, "hp").setInt(chr, 500);
        field(MapleCharacter.class, "localmaxhp").setInt(chr, 500);
        field(MapleCharacter.class, "mp").setInt(chr, 300);
        field(MapleCharacter.class, "localmaxmp").setInt(chr, 300);
        field(MapleCharacter.class, "localdex").setInt(chr, 200);
        field(MapleCharacter.class, "localluk").setInt(chr, 50);
        field(MapleCharacter.class, "localwatk").setInt(chr, 30);
        field(MapleCharacter.class, "autoban").set(chr, new client.autoban.AutobanManager(chr));
        chr.setPosition(new Point(0, 0));
        TestClient client = new TestClient();
        chr.setClient(client);
        client.setPlayer(chr);
        boolean transition = Arrays.asList(args).contains("transition");
        boolean contention = Arrays.asList(args).contains("contention");
        if (!transition) new PlayerMapTransitionHandler().handlePacket(null, client);
        chr.getInventory(MapleInventoryType.EQUIPPED).addItemFromDB(new Equip(1472030, (short) -11));
        chr.getInventory(MapleInventoryType.USE).addItemFromDB(new Item(2070016, (short) 1, (short) 100));
        MapleCharacter initialController = chr;
        if (contention) {
            check(trace && !transition, "contention diagnostic requires trace and no transition");
            initialController = (MapleCharacter) factory.invoke(null, map, true, false, false, true);
            field(MapleCharacter.class, "id").setInt(initialController, 92);
            initialController.setMapTransitionComplete();
            initialController.setPosition(new Point(0, 0));
        }
        field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(initialController)));
        TestMob mob = new TestMob();
        mob.setPosition(new Point(140, 0));
        mob.setFh(1);
        boolean scheduled = args.length > 0 && args[0].equals("scheduled");
        if (scheduled) {
            TimerManager.getInstance().start();
            map.scheduled = true;
        }
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new OutputStream() {
            public void write(int b) {
                originalOut.write(b);
                captured.write(b);
            }
        }, true));
        try {
        map.spawnMonster(mob);
        check(mob.hasServerGroundAttack() == !fallback, "WZ authority attachment/config fallback");
        check(mob.getController() == initialController && !mob.isControllerHasAggro(), "normal idle controller");
        if (contention) {
            check(!scheduled, "contention scenario uses manual ticks");
            field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(initialController, chr)));
        }
        FlatBufferAttackHandler.forget(chr.getId());
        int mobHp = mob.getHp();
        boolean legacy = Arrays.asList(args).contains("legacy");
        if (contention) {
            MonitoredReentrantLock lock = (MonitoredReentrantLock) field(MapleMonster.class, "aggroUpdateLock").get(mob);
            CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                lock.lock();
                try {
                    held.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("lock release timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally { lock.unlock(); }
            });
            holder.start();
            try {
                check(held.await(5, TimeUnit.SECONDS), "controller lock acquired by other thread");
                new FlatBufferAttackHandler().handleFlatBufferAttack(request(mob.getObjectId()), client);
                for (int i = 0; i < 5; i++) mob.tickGroundAuthority();
                check(mob.getHp() < mobHp && mob.getController() == initialController
                        && !mob.isControllerHasAggro(), "contended hit/ticks must not block or change controller");
            } finally {
                release.countDown();
                holder.join(5000);
            }
            check(!holder.isAlive(), "controller lock holder completed");
            check(mob.getHp() < mobHp && mob.getController() == initialController
                    && !mob.isControllerHasAggro(), "contended real hit exposes dropped controller switch");
            check(captured.toString("UTF-8").contains("controller action=contended"),
                    "contended switch is diagnosed");
            if (fallback) {
                for (int i = 0; i < 10; i++) mob.tickGroundAuthority();
                check(mob.getController() == initialController && !mob.isControllerHasAggro(),
                        "legacy fallback must retain existing contended switch behavior");
                check(!captured.toString("UTF-8").contains("damage_retry_succeeded"),
                        "no new retry on legacy mobs");
                System.out.println("PASS real-hit legacy fallback contention remains unchanged");
                return;
            }
            if (Arrays.asList(args).contains("departed")) {
                field(MapleCharacter.class, "loggedIn").setBoolean(chr, false);
                mob.tickGroundAuthority();
                check(mob.getController() == initialController && !mob.isControllerHasAggro(),
                        "offline attacker must not acquire pending controller");
                check(captured.toString("UTF-8").contains("damage_retry_discarded"),
                        "invalid pending attacker diagnosed");
                field(MapleCharacter.class, "loggedIn").setBoolean(chr, true);
                mob.tickGroundAuthority();
                check(mob.getController() == initialController && !mob.isControllerHasAggro(),
                        "discarded pending hit must not resurrect on login");
                System.out.println("PASS contended real hit discards offline target without stale aggro");
                return;
            }
            if (Arrays.asList(args).contains("idle")) {
                mob.aggroSwitchController(chr, false);
                check(mob.getController() == chr && !mob.isControllerHasAggro(),
                        "normal intervening idle assignment");
            }
            // No second hit or injected controller/aggro: authority tick must
            // recover the single real hit lost to controller-lock contention.
            mob.tickGroundAuthority();
            check(mob.getController() == chr && mob.isControllerHasAggro(),
                    "single contended hit must recover aggro on next tick after release");
            check(captured.toString("UTF-8").contains("damage_retry_succeeded"),
                    "successful pending damage retry is diagnosed");
        } else if (legacy) legacyHit(mob.getObjectId(), client);
        else new FlatBufferAttackHandler().handleFlatBufferAttack(request(mob.getObjectId()), client);
        check(mob.getHp() < mobHp && mob.isAlive(), "production request damaged Lupin");
        check(mob.getController() == chr && mob.isControllerHasAggro(), "real hit sets aggro");
        // Player accuracy was needed for the real hit; remove avoidance only
        // afterwards so this bounded fixture does not randomly miss forever.
        field(MapleCharacter.class, "localdex").setInt(chr, 0);
        field(MapleCharacter.class, "localluk").setInt(chr, 0);
        if (transition) {
            check(!scheduled && chr.isChangingMaps(), "transition scenario uses manual ticks");
            for (int i = 0; i < 25; i++) mob.tickGroundAuthority();
            for (byte[] bytes : client.packets) {
                ByteBuffer bb = ByteBuffer.wrap(bytes);
                check(bytes.length < 8 || !Packet.PacketBufferHasIdentifier(bb)
                        || Packet.getRootAsPacket(bb).serverMessageType()
                            != ServerMessage.protocol_generated_maple_map_MobAttackEvent,
                        "transitioning player must not receive attacks");
            }
            if (trace) {
                String before = captured.toString("UTF-8");
                check(before.contains("state target=transition"), "transition diagnostic");
                check(before.indexOf("state target=transition") == before.lastIndexOf("state target=transition"),
                        "unchanged state must not log every tick");
                Thread.sleep(1100); // Allow the rate-limited eligible transition to be reported.
            }
            new PlayerMapTransitionHandler().handlePacket(null, client);
            check(!chr.isChangingMaps(), "actual transition handler restores eligibility");
        }
        if (scheduled) {
            long deadline = System.currentTimeMillis() + 15000;
            while ((!hasDamagingImpact(client)
                    || (trace && !captured.toString("UTF-8").contains("phase=IMPACT")))
                    && System.currentTimeMillis() < deadline) Thread.sleep(25);
        } else {
            for (int i = 0; i < 300 && chr.getHp() == 500; i++) mob.tickGroundAuthority();
        }
        List<Integer> phases = new ArrayList<>();
        for (byte[] bytes : client.packets) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            if (bytes.length < 8 || !Packet.PacketBufferHasIdentifier(bb)) continue;
            Packet packet = Packet.getRootAsPacket(bb);
            if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobAttackEvent) {
                MobAttackEvent event = (MobAttackEvent) packet.serverMessage(new MobAttackEvent());
                if (event.targetId() == chr.getId()) phases.add((int) event.phase());
            }
        }
        check(phases.contains((int) MobAttackPhase.Start) && phases.contains((int) MobAttackPhase.Launch)
                && phases.contains((int) MobAttackPhase.Impact) && (!scheduled || chr.getHp() < 500),
                "production hit results in attack lifecycle and, when scheduled, target damage: " + phases);
        String logs = captured.toString("UTF-8");
        if (trace) {
            check(logs.contains("[LupinTrace]") && logs.contains("attach configured=true")
                    && logs.contains("authority=true") && logs.contains("profile=true")
                    && logs.contains("controller action=assigned")
                    && logs.contains("hit damage=")
                    && (contention || logs.contains("attackerControls=true"))
                    && logs.contains("state target=") && logs.contains("phase=START")
                    && logs.contains("phase=LAUNCH") && logs.contains("phase=IMPACT")
                    && logs.contains("broadcast=returned"), "diagnostic covers actual hit and broadcast phases");
            if (transition) check(logs.contains("state target=eligible"), "eligibility recovery diagnostic");
        } else {
            check(!logs.contains("[LupinTrace]"), "diagnostic is disabled by default");
        }
        System.out.println("PASS real " + (legacy ? "legacy" : "FlatBuffer")
                + " player hit, controller aggro and attack phases "
                + (scheduled ? "(scheduled) " : "(manual) ") + phases);
        } finally {
            if (scheduled) TimerManager.getInstance().stop();
            System.setOut(originalOut);
        }
    }
}
