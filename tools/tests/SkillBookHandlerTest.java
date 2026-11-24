package net.server.channel.handlers;

import client.MapleCharacter;
import client.MapleClient;
import client.MapleJob;
import client.Skill;
import client.SkillFactory;
import client.inventory.Item;
import client.inventory.MapleInventoryType;
import com.google.flatbuffers.FlatBufferBuilder;
import config.YamlConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.MapleServerHandler;
import net.PacketProcessor;
import net.opcodes.RecvOpcode;
import org.apache.mina.core.session.IoSession;
import protocol.generated.maple.character.*;
import protocol.generated.maple.network.*;
import server.MapleItemInformationProvider;
import server.TimerManager;
import server.maps.MapleMap;
import tools.Randomizer;
import tools.data.input.*;
import tools.packets.character.SkillBookPackets;

/**
 * Offline production-handler/dispatcher fixture. No sockets or real database.
 * Run from HeavenMS with -Dwzpath=wz after mvn -DskipTests compile.
 * Optional argument: BOOK_HEX emitted by the client's SkillBookPacketTest.
 */
public final class SkillBookHandlerTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // The item provider loads monster-card metadata in its constructor. Supply
    // only that empty query; never connect this test to the shared live database.
    private static void isolateDatabase() throws SQLException {
        YamlConfig.config.server.DB_URL = "jdbc:skillbook-fixture:";
        DriverManager.registerDriver(new Driver() {
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:skillbook-fixture:"); }
            public Connection connect(String url, Properties props) {
                if (!acceptsURL(url)) return null;
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("close")) return null;
                    if (m.getName().equals("isClosed")) return false;
                    if (m.getName().equals("prepareStatement")
                            && a[0].equals("SELECT cardid, mobid FROM monstercarddata")) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (q, n, b) -> {
                            if (n.getName().equals("close")) return null;
                            if (n.getName().equals("isClosed")) return false;
                            if (n.getName().equals("executeQuery"))
                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                        new Class<?>[]{ResultSet.class}, (r, o, c) -> {
                                    if (o.getName().equals("next")) return false;
                                    if (o.getName().equals("close")) return null;
                                    if (o.getName().equals("isClosed")) return false;
                                    throw new SQLException("Unexpected result call: " + o);
                                });
                            throw new SQLException("Unexpected statement: " + n);
                        });
                    }
                    throw new SQLException("Unexpected DB call: " + m);
                });
            }
            public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
        });
    }

    private static final class RecordingClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        RecordingClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) { packets.add(packet); }
    }

    private static final class RecordingMap extends MapleMap {
        final RecordingClient observer = new RecordingClient();
        MapleCharacter excluded;
        RecordingMap() { super(100000000, 0, 1, 100000000, 1); }
        @Override public void broadcastMessage(MapleCharacter source, byte[] packet) {
            excluded = source;
            observer.announce(packet);
        }
    }

    private static MapleCharacter player(RecordingMap map) throws Exception {
        Constructor<MapleCharacter> ctor = MapleCharacter.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MapleCharacter chr = ctor.newInstance();
        field(MapleCharacter.class, "id").setInt(chr, 123);
        field(MapleCharacter.class, "map").set(chr, map);
        field(MapleCharacter.class, "job").set(chr, MapleJob.NIGHTLORD);
        field(MapleCharacter.class, "hp").setInt(chr, 1000);
        ((AtomicBoolean) field(MapleCharacter.class, "awayFromWorld").get(chr)).set(false);
        chr.setMapTransitionComplete();
        RecordingClient c = new RecordingClient();
        field(MapleClient.class, "loggedIn").setBoolean(c, true);
        chr.setClient(c);
        c.setPlayer(chr);
        return chr;
    }

    @SuppressWarnings("unchecked")
    private static void skill(MapleCharacter chr, int level, int cap) throws Exception {
        ((Map<Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr))
                .put(SkillFactory.getSkill(4121007), new MapleCharacter.SkillEntry((byte) level, cap, -1));
    }

    private static Item book(MapleCharacter chr, int itemId, int quantity) {
        Item book = new Item(itemId, (short) 1, (short) quantity);
        chr.getInventory(MapleInventoryType.USE).addItemFromDB(book);
        return book;
    }

    private static byte[] request(short slot, int itemId) {
        return request(slot, itemId, ProtocolVersion.V1, ServerMessage.NONE, true);
    }

    private static byte[] request(short slot, int itemId, int version, byte serverType, boolean includeBody) {
        FlatBufferBuilder b = new FlatBufferBuilder(64);
        int request = SkillBookUseRequest.createSkillBookUseRequest(b, slot, itemId);
        Packet.startPacket(b);
        Packet.addVersion(b, version);
        Packet.addServerMessageType(b, serverType);
        Packet.addClientMessageType(b, ClientMessage.protocol_generated_maple_character_SkillBookUseRequest);
        if (includeBody) Packet.addClientMessage(b, request);
        Packet.finishPacketBuffer(b, Packet.endPacket(b));
        return b.sizedByteArray();
    }

    private static IoSession session(RecordingClient client) {
        return (IoSession) Proxy.newProxyInstance(IoSession.class.getClassLoader(),
                new Class<?>[]{IoSession.class}, (p, m, a) -> {
            if (m.getName().equals("getAttribute") && a[0].equals(MapleClient.CLIENT_KEY)) return client;
            throw new AssertionError("Unexpected session call: " + m);
        });
    }

    private static Packet packet(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(Packet.PacketBufferHasIdentifier(b), "MSFB identifier, no opcode");
        return Packet.getRootAsPacket(b);
    }

    private static SkillBookUseResult result(RecordingClient c, int status) {
        Packet p = packet(c.packets.get(c.packets.size() - 1));
        check(p.serverMessageType() == ServerMessage.protocol_generated_maple_character_SkillBookUseResult,
                "server book result union");
        SkillBookUseResult r = (SkillBookUseResult) p.serverMessage(new SkillBookUseResult());
        check(r.status() == status && r.characterId() == 123, "outcome and character identity");
        return r;
    }

    private static void rejected(MapleServerHandler handler, MapleCharacter chr, byte[] request) {
        RecordingClient c = (RecordingClient) chr.getClient();
        RecordingMap map = (RecordingMap) chr.getMap();
        int broadcasts = map.observer.packets.size();
        handler.messageReceived(session(c), request);
        result(c, SkillBookUseStatus.Rejected);
        check(map.observer.packets.size() == broadcasts, "rejection is private, no animation broadcast");
    }

    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_DEBUG = false;
        isolateDatabase();
        SkillFactory.loadAllSkills();
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        Map<String, Integer> data = ii.getSkillStats(2280010, 412);
        check(data.get("skillid") == 4121007 && data.get("masterLevel") == 10
                && data.get("success") == 100 && data.get("reqSkillLevel") == 0,
                "real WZ: 2280010 is Triple Throw 10, 100%, no learned-level prerequisite");
        check(ii.getSkillStats(2280010, 422).get("skillid") == 0, "wrong job has no book skill");
        check(ii.getSkillStats(2000000, 412) == null, "normal item has no book metadata");
        data = ii.getSkillStats(2290084, 412);
        check(data.get("skillid") == 4121007 && data.get("masterLevel") == 20
                && data.get("success") == 70 && data.get("reqSkillLevel") == 5,
                "real WZ: Triple Throw mastery 20 requires level 5, 70%");
        data = ii.getSkillStats(2290085, 412);
        check(data.get("masterLevel") == 30 && data.get("success") == 50 && data.get("reqSkillLevel") == 15,
                "real WZ: Triple Throw mastery 30 requires level 15, 50%");
        check(!SkillBookHandler.meetsBookRequirements(4, 10, 5, 20), "below required skill level");
        check(!SkillBookHandler.meetsBookRequirements(5, 20, 5, 20), "already at book cap");
        check(!SkillBookHandler.meetsBookRequirements(0, 0, 0, 0), "invalid zero cap");
        for (int roll = 0; roll < 100; ++roll) {
            if (!SkillBookHandler.succeeds(100, roll) || SkillBookHandler.succeeds(0, roll)
                    || SkillBookHandler.succeeds(70, roll) != (roll < 70))
                throw new AssertionError("chance boundary " + roll);
        }
        check(true, "all 100 chance boundaries (no scroll-roll multiplier)");

        TimerManager.getInstance().start(); // process-local dispatcher idle timer only
        try {
            MapleServerHandler handler = new MapleServerHandler(0, 1);
            check(PacketProcessor.getProcessor(0, 1).getHandler((short) RecvOpcode.USE_ITEM.getValue()) instanceof UseItemHandler,
                    "unrelated legacy USE_ITEM remains registered");
            check(PacketProcessor.getProcessor(0, 1).getHandler((short) RecvOpcode.USE_SKILL_BOOK.getValue()) instanceof SkillBookHandler,
                    "legacy book adapter remains registered");
            for (int itemId : new int[]{2040000, 2060000, 2061000, 2070000, 2330000, 2030101}) {
                MapleCharacter user = player(new RecordingMap());
                Item stack = book(user, itemId, 2);
                byte[] request = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0).putShort((short) 1).putInt(itemId).array();
                new UseItemHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                        new ByteArrayByteStream(request)), user.getClient());
                check(stack.getQuantity() == 2 && !((RecordingClient) user.getClient()).packets.isEmpty(),
                        "non-consumable remains in inventory and actions are re-enabled");
            }
            for (int itemId : new int[]{2000000, 2010000, 2022000, 2050004}) {
                MapleCharacter user = player(new RecordingMap());
                Item stack = book(user, itemId, 2);
                byte[] request = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0).putShort((short) 1).putInt(itemId).array();
                new UseItemHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                        new ByteArrayByteStream(request)), user.getClient());
                check(stack.getQuantity() == 1, "valid consumable removes exactly one item");
            }
            // Regression: the reported inventory click arrived as USE_ITEM,
            // not USE_SKILL_BOOK. It must never enter potion-effect loading.
            byte[] oldClick = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) RecvOpcode.USE_ITEM.getValue())
                    .putInt(0).putShort((short) 1).putInt(2280010).array();
            MapleCharacter oldPlayer = player(new RecordingMap());
            Item oldStack = book(oldPlayer, 2280010, 2);
            handler.messageReceived(session((RecordingClient) oldPlayer.getClient()), oldClick);
            result((RecordingClient) oldPlayer.getClient(), SkillBookUseStatus.Success);
            check(oldStack.getQuantity() == 1
                    && oldPlayer.getMasterLevel(SkillFactory.getSkill(4121007)) == 10
                    && oldPlayer.getSkillLevel(SkillFactory.getSkill(4121007)) == 0,
                    "legacy USE_ITEM book click unlocks cap without potion effects");
            rejected(handler, oldPlayer, oldClick);
            check(oldStack.getQuantity() == 1, "legacy USE_ITEM replay cannot consume another unlock");
            oldPlayer = player(new RecordingMap());
            field(MapleCharacter.class, "job").set(oldPlayer, MapleJob.SHADOWER);
            oldStack = book(oldPlayer, 2280010, 1);
            rejected(handler, oldPlayer, oldClick);
            check(oldStack.getQuantity() == 1, "legacy USE_ITEM wrong job preserves the book");
            field(MapleCharacter.class, "job").set(oldPlayer, MapleJob.NIGHTLORD);
            field(MapleCharacter.class, "hp").setInt(oldPlayer, 0);
            rejected(handler, oldPlayer, oldClick);
            check(oldStack.getQuantity() == 1, "legacy USE_ITEM dead player preserves the book");
            oldPlayer = player(new RecordingMap());
            oldStack = book(oldPlayer, 2000000, 2);
            rejected(handler, oldPlayer, oldClick);
            check(oldStack.getQuantity() == 2, "legacy USE_ITEM stale book slot preserves replacement potion");

            byte[] unlock = args.length == 0 ? request((short) 1, 2280010)
                    : HexFormat.of().parseHex(args[0]);
            SkillBookUseRequest decoded = (SkillBookUseRequest) packet(unlock).clientMessage(new SkillBookUseRequest());
            check(decoded.slot() == 1 && decoded.itemId() == 2280010, "client intent contains only slot/item");
            RecordingMap map = new RecordingMap();
            MapleCharacter chr = player(map);
            RecordingClient c = (RecordingClient) chr.getClient();
            Item stack = book(chr, 2280010, 2);
            handler.messageReceived(session(c), unlock);
            SkillBookUseResult r = result(c, SkillBookUseStatus.Success);
            check(r.itemId() == 2280010 && r.skillId() == 4121007 && r.masterLevel() == 10,
                    "result carries resolved WZ values");
            check(stack.getQuantity() == 1 && chr.getMasterLevel(SkillFactory.getSkill(4121007)) == 10
                    && chr.getSkillLevel(SkillFactory.getSkill(4121007)) == 0,
                    "unlock consumes exactly one, raises cap, preserves learned level");
            check(c.packets.stream().filter(bytes -> bytes.length >= 8
                    && Packet.PacketBufferHasIdentifier(ByteBuffer.wrap(bytes))
                    && Packet.getRootAsPacket(ByteBuffer.wrap(bytes)).serverMessageType()
                    == ServerMessage.protocol_generated_maple_character_SkillUpdate).count() == 1,
                    "existing FlatBuffer SkillUpdate emitted exactly once");
            check(map.excluded == chr && map.observer.packets.size() == 1
                    && Arrays.equals(c.packets.get(c.packets.size() - 1), map.observer.packets.get(0)),
                    "owner and other players receive the same result, no duplicate owner effect");
            rejected(handler, chr, unlock);
            check(stack.getQuantity() == 1, "replayed unlock cannot consume after cap is reached");
            rejected(handler, chr, request((short) 0, 2280010));
            rejected(handler, chr, request((short) 2, 2280010));
            rejected(handler, chr, request((short) 1, 2290084));
            check(stack.getQuantity() == 1, "invalid and stale slots preserve inventory");

            chr = player(new RecordingMap());
            c = (RecordingClient) chr.getClient();
            stack = book(chr, 2000000, 2);
            rejected(handler, chr, unlock);
            rejected(handler, chr, request((short) 1, 2000000));
            check(stack.getQuantity() == 2, "replacement potion and non-book requests cannot consume");
            chr = player(new RecordingMap());
            stack = book(chr, 2280010, 1);
            field(MapleCharacter.class, "job").set(chr, MapleJob.SHADOWER);
            rejected(handler, chr, unlock);
            check(stack.getQuantity() == 1, "wrong job cannot consume");
            field(MapleCharacter.class, "job").set(chr, MapleJob.NIGHTLORD);
            field(MapleCharacter.class, "hp").setInt(chr, 0);
            rejected(handler, chr, unlock);
            check(stack.getQuantity() == 1, "dead character cannot consume");
            field(MapleCharacter.class, "hp").setInt(chr, 1000);
            c = (RecordingClient) chr.getClient();
            java.util.concurrent.Semaphore actions =
                    (java.util.concurrent.Semaphore) field(MapleClient.class, "actionsSemaphore").get(c);
            int permits = actions.drainPermits();
            try { rejected(handler, chr, unlock); } finally { actions.release(permits); }
            check(stack.getQuantity() == 1, "busy client does not consume");

            chr = player(new RecordingMap());
            c = (RecordingClient) chr.getClient();
            stack = book(chr, 2290084, 2);
            skill(chr, 4, 10);
            rejected(handler, chr, request((short) 1, 2290084));
            check(stack.getQuantity() == 2, "ineligible mastery does not consume");
            skill(chr, 5, 10);
            Random random = (Random) field(Randomizer.class, "rand").get(null);
            random.setSeed(0); // nextInt(100) == 60, inside 70%
            handler.messageReceived(session(c), request((short) 1, 2290084));
            result(c, SkillBookUseStatus.Success);
            check(stack.getQuantity() == 1 && chr.getMasterLevel(SkillFactory.getSkill(4121007)) == 20
                    && chr.getSkillLevel(SkillFactory.getSkill(4121007)) == 5, "eligible mastery success");
            stack = book(chr, 2290085, 1);
            skill(chr, 15, 20);
            random.setSeed(0); // 60 loses a 50% roll
            handler.messageReceived(session(c), request((short) 1, 2290085));
            result(c, SkillBookUseStatus.Failed);
            check(stack.getQuantity() == 0 && chr.getMasterLevel(SkillFactory.getSkill(4121007)) == 20
                    && chr.getSkillLevel(SkillFactory.getSkill(4121007)) == 15,
                    "failed roll consumes exactly one, preserves skill and cap");
            rejected(handler, chr, request((short) 1, 2290085));
            check(((RecordingMap) chr.getMap()).observer.packets.size() == 2,
                    "both successful and failed attempts broadcast, empty-slot retry does not");

            chr = player(new RecordingMap());
            c = (RecordingClient) chr.getClient();
            stack = book(chr, 2280010, 0);
            rejected(handler, chr, unlock);
            check(stack.getQuantity() == 0, "zero-quantity slot cannot grant a skill");
            stack = book(chr, 2280010, 1);
            ((AtomicBoolean) field(MapleCharacter.class, "awayFromWorld").get(chr)).set(true);
            rejected(handler, chr, unlock);
            ((AtomicBoolean) field(MapleCharacter.class, "awayFromWorld").get(chr)).set(false);
            ((AtomicBoolean) field(MapleCharacter.class, "mapTransitioning").get(chr)).set(true);
            rejected(handler, chr, unlock);
            chr.setMapTransitionComplete();
            check(stack.getQuantity() == 1, "cash shop/transition state preserves inventory");
            int before = c.packets.size();
            field(MapleClient.class, "loggedIn").setBoolean(c, false);
            handler.messageReceived(session(c), unlock);
            field(MapleClient.class, "loggedIn").setBoolean(c, true);
            MapleServerHandler login = new MapleServerHandler();
            login.messageReceived(session(c), unlock);
            handler.messageReceived(session(c), request((short) 1, 2280010, 2, ServerMessage.NONE, true));
            handler.messageReceived(session(c), request((short) 1, 2280010, ProtocolVersion.V1,
                    ServerMessage.protocol_generated_maple_character_SkillBookUseResult, true));
            handler.messageReceived(session(c), request((short) 1, 2280010, ProtocolVersion.V1, ServerMessage.NONE, false));
            handler.messageReceived(session(c), Arrays.copyOf(unlock, unlock.length - 1));
            check(c.packets.size() == before && stack.getQuantity() == 1
                    && chr.getMasterLevel(SkillFactory.getSkill(4121007)) == 0,
                    "login/unauthenticated/wrong-version/wrong-direction/missing/truncated payloads do not mutate");

            // Legacy compatibility uses the same server behavior, not a second implementation.
            chr = player(new RecordingMap());
            stack = book(chr, 2280010, 1);
            byte[] legacy = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0).putShort((short) 1).putInt(2280010).array();
            new SkillBookHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                    new ByteArrayByteStream(legacy)), chr.getClient());
            check(stack.getQuantity() == 0 && chr.getMasterLevel(SkillFactory.getSkill(4121007)) == 10,
                    "legacy adapter still grants book using shared authoritative path");
            for (int status : new int[]{SkillBookUseStatus.Rejected, SkillBookUseStatus.Failed, SkillBookUseStatus.Success}) {
                c = new RecordingClient();
                c.announce(SkillBookPackets.result(123, 2280010, 4121007, 10, status));
                result(c, status);
            }
            System.out.println("RESULT_HEX=" + HexFormat.of().formatHex(
                    SkillBookPackets.result(123, 2280010, 4121007, 10, SkillBookUseStatus.Success)));
        } finally {
            TimerManager.getInstance().stop();
        }
    }
}
