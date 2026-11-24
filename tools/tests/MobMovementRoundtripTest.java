import client.MapleCharacter;
import client.MapleClient;
import config.YamlConfig;
import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import net.server.channel.handlers.MoveLifeHandler;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.maps.MapleMap;
import server.maps.MapleMapObject;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;
import tools.data.input.SeekableLittleEndianAccessor;

/** Real MOVE_LIFE parser/update/rebroadcast on native client-built payloads.
 * Reuses the existing controller fixture solely for offline character/DB setup.
 * No movement parser, position update, controller authorization or serializer is mocked.
 */
public class MobMovementRoundtripTest {
    private static class MovementOnlyMonster extends MapleMonster {
        MovementOnlyMonster(MapleMonsterStats stats) { super(100100, stats); }
        @Override public int canUseAttack(int attackPos, boolean isSkill) {
            // These native payloads have rawActivity=0, not a mob attack.
            // Suppress only the unrelated attack-info/global-drop DB loader.
            if (attackPos != -12 || isSkill) throw new AssertionError("unexpected mob attack");
            return -1;
        }
    }
    private static class RecordingClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        RecordingClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) { packets.add(packet); }
    }
    private static class RecordingMap extends MapleMap {
        MapleMonster monster;
        byte[] broadcast;
        RecordingMap() { super(100000100, 0, 1, 100000000, 1); }
        @Override public MapleMapObject getMapObject(int oid) {
            return oid == 123 ? monster : null;
        }
        @Override public void broadcastMessage(MapleCharacter source, byte[] packet, Point position) {
            broadcast = packet; // Only the outbound delivery sink is isolated.
        }
    }
    private static SeekableLittleEndianAccessor reader(byte[] data) {
        return new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(data));
    }
    private static byte[] hex(String value) {
        byte[] data = new byte[value.length() / 2];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return data;
    }
    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        Method isolate = MonsterControllerAssignmentTest.class.getDeclaredMethod("isolateDatabase");
        isolate.setAccessible(true);
        isolate.invoke(null);
        RecordingMap map = new RecordingMap();
        Method playerFactory = MonsterControllerAssignmentTest.class.getDeclaredMethod(
                "player", MapleMap.class, boolean.class, boolean.class, boolean.class, boolean.class);
        playerFactory.setAccessible(true);
        MapleCharacter player = (MapleCharacter) playerFactory.invoke(null, map, true, false, false, true);
        player.setMapTransitionComplete();
        RecordingClient client = new RecordingClient();
        player.setClient(client);
        client.setPlayer(player);
        MapleMonsterStats stats = new MapleMonsterStats();
        stats.setHp(1000);
        map.monster = new MovementOnlyMonster(stats);
        map.monster.setObjectId(123);
        map.monster.setMap(map);
        Method controller = MapleMonster.class.getDeclaredMethod("setController", MapleCharacter.class);
        controller.setAccessible(true);
        controller.invoke(map.monster, player);
        List<String> fixtures = new ArrayList<>(List.of(args));
        if (args.length == 0) {
            Scanner input = new Scanner(System.in);
            while (input.hasNextLine()) {
                String line = input.nextLine();
                int marker = line.indexOf("_HEX=");
                if (marker >= 0) fixtures.add(line.substring(marker + 5));
            }
        }
        for (String fixture : fixtures) {
            byte[] data = hex(fixture);
            SeekableLittleEndianAccessor expected = reader(data);
            if (expected.readShort() != 188) throw new AssertionError("MOVE_LIFE opcode");
            expected.skip(25);
            Point start = new Point(expected.readShort(), expected.readShort());
            if (expected.readByte() != 1 || expected.readByte() != 0)
                throw new AssertionError("one absolute fragment");
            Point end = new Point(expected.readShort(), expected.readShort() - 2);
            expected.skip(6);
            byte stance = expected.readByte();
            expected.readShort();
            if (expected.available() != 0) throw new AssertionError("fixture boundary");
            map.monster.setPosition(start);
            map.broadcast = null;
            SeekableLittleEndianAccessor incoming = reader(data);
            incoming.readShort(); // MapleServerHandler consumes only the opcode.
            new MoveLifeHandler().handlePacket(incoming, client);
            if (incoming.available() != 0 || !end.equals(map.monster.getPosition())
                    || map.monster.getStance() != stance || map.broadcast == null)
                throw new AssertionError("MOVE_LIFE parse/update: " + start + " -> "
                        + map.monster.getPosition() + ", expected " + end);
            if (map.broadcast.length != 32) throw new AssertionError("rebroadcast boundary");
            for (int i = 0; i < 15; ++i)
                if (map.broadcast[17 + i] != data[31 + i])
                    throw new AssertionError("movement bytes not preserved");
            StringBuilder out = new StringBuilder();
            for (byte b : map.broadcast) out.append(String.format("%02x", b & 255));
            System.out.println("SERVER_HEX=" + out);
        }
        if (fixtures.isEmpty()) throw new AssertionError("no native fixtures");
        System.out.println("PASS " + fixtures.size() + " real MOVE_LIFE authoritative position/rebroadcast cases");
    }
}
