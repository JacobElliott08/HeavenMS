import client.MapleCharacter;
import client.MapleClient;
import config.YamlConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.opcodes.SendOpcode;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.maps.MapleMap;

/**
 * Offline regression fixture; no database, sockets, or map timers.
 * Character static initialization reads the existing WZ directory.
 * Run from the HeavenMS root after Maven compile, using its dependency classpath:
 * javac --release 21 -cp "target/classes;<dependencies>" -d target/controller-tests tools/tests/MonsterControllerAssignmentTest.java
 * java -Dwzpath=wz -cp "target/controller-tests;target/classes;<dependencies>" MonsterControllerAssignmentTest
 * Compile first so the JDBC fixture and backend share DriverManager's classloader.
 */
public class MonsterControllerAssignmentTest {
    private static int failures;

    private static void isolateDatabase() throws SQLException {
        // MapleCharacter's item-provider initializer reads monster-card data.
        // Empty card/global-drop reads only; real WZ mob stats also initialize
        // the global-drop provider. Never contact the configured DB.
        YamlConfig.config.server.DB_URL = "jdbc:controller-fixture:";
        DriverManager.registerDriver(new Driver() {
            public Connection connect(String url, Properties info) throws SQLException {
                if (!acceptsURL(url)) return null;
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("isClosed")) return false;
                    if (method.getName().equals("prepareStatement")
                            && (args[0].equals("SELECT cardid, mobid FROM monstercarddata")
                                || args[0].equals("SELECT * FROM drop_data_global WHERE chance > 0"))) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                            if (m.getName().equals("close")) return null;
                            if (m.getName().equals("isClosed")) return false;
                            if (m.getName().equals("executeQuery")) {
                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                        new Class<?>[]{ResultSet.class}, (r, n, b) -> {
                                            if (n.getName().equals("next") || n.getName().equals("isClosed")) return false;
                                            if (n.getName().equals("close")) return null;
                                            throw new AssertionError("Unexpected result method: " + n);
                                        });
                            }
                            throw new AssertionError("Unexpected statement method: " + m);
                        });
                    }
                    throw new AssertionError("Unexpected database access: " + method);
                });
            }
            public boolean acceptsURL(String url) { return url.equals("jdbc:controller-fixture:"); }
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
        });
    }

    private static final class RecordingClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();

        RecordingClient() {
            super(null, null, null);
        }

        @Override
        public void announce(byte[] packet) {
            packets.add(packet);
        }
    }

    private static final class TestMonster extends MapleMonster {
        TestMonster(MapleMap map) {
            super(120100, stats());
            setMap(map);
            setObjectId(123);
        }

        @Override
        public void aggroUpdatePuppetVisibility() {
            // Only suppress the unrelated channel-service timer.
        }
    }

    private static MapleMonsterStats stats() {
        MapleMonsterStats stats = new MapleMonsterStats();
        stats.setHp(10);
        return stats;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static MapleCharacter player(MapleMap map, boolean loggedIn, boolean away,
                                         boolean hidden, boolean alive) throws Exception {
        Constructor<MapleCharacter> constructor = MapleCharacter.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MapleCharacter player = constructor.newInstance();
        field(MapleCharacter.class, "map").set(player, map);
        field(MapleCharacter.class, "loggedIn").setBoolean(player, loggedIn);
        ((AtomicBoolean) field(MapleCharacter.class, "awayFromWorld").get(player)).set(away);
        field(MapleCharacter.class, "hidden").setBoolean(player, hidden);
        field(MapleCharacter.class, "hp").setInt(player, alive ? 10 : 0);
        RecordingClient client = new RecordingClient();
        player.setClient(client);
        client.setPlayer(player);
        return player;
    }

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS " : "FAIL ") + name);
        if (!condition) failures++;
    }

    private static void assignment(String name, MapleMap map, MapleCharacter expected,
                                   MapleCharacter... players) throws Exception {
        // Populate the real snapshot source without addPlayer's scripts/timers.
        field(MapleMap.class, "characters").set(map, new ArrayList<>(Arrays.asList(players)));
        TestMonster monster = new TestMonster(map);
        monster.aggroUpdateController();
        check(name + ": controller", monster.getController() == expected);
        for (MapleCharacter player : players) {
            RecordingClient client = (RecordingClient) player.getClient();
            check(name + ": packet/tracking", client.packets.size() == (player == expected ? 1 : 0)
                    && player.getNumControlledMonsters() == (player == expected ? 1 : 0));
            if (player == expected && !client.packets.isEmpty()) {
                byte[] packet = client.packets.get(0);
                int opcode = (packet[0] & 255) | ((packet[1] & 255) << 8);
                int oid = (packet[3] & 255) | ((packet[4] & 255) << 8)
                        | ((packet[5] & 255) << 16) | ((packet[6] & 255) << 24);
                check(name + ": legacy control mode 1", opcode == SendOpcode.SPAWN_MONSTER_CONTROL.getValue()
                        && opcode == 238 && packet[2] == 1 && oid == 123);
                monster.aggroUpdateController();
                check(name + ": active controller retained", monster.getController() == expected
                        && client.packets.size() == 1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Process-local only: keep lock diagnostics from scheduling timers.
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        // This fixture asserts legacy-only packet counts, independent of YAML.
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
        isolateDatabase();
        MapleMap map = new MapleMap(100000000, 0, 1, 100000000, 1);
        MapleMap otherMap = new MapleMap(100000001, 0, 1, 100000000, 1);
        MapleCharacter eligible = player(map, true, false, false, true);
        assignment("offline candidate before eligible", map, eligible,
                player(map, false, false, false, true), eligible);
        eligible = player(map, true, false, false, true);
        assignment("away candidate before eligible", map, eligible,
                player(map, true, true, false, true), eligible);
        eligible = player(map, true, false, false, true);
        assignment("wrong-map snapshot before eligible", map, eligible,
                player(otherMap, true, false, false, true), eligible);
        eligible = player(map, true, false, false, true);
        assignment("hidden excluded", map, eligible,
                player(map, true, false, true, true), eligible);
        assignment("hidden-only map stays uncontrolled", map, null,
                player(map, true, false, true, true));
        assignment("ineligible-only map stays uncontrolled", map, null,
                player(map, false, false, false, true), player(map, true, true, false, true),
                player(otherMap, true, false, false, true));
        eligible = player(map, true, false, false, true);
        assignment("alive preferred to dead", map, eligible,
                player(map, true, false, false, false), eligible);
        eligible = player(map, true, false, false, false);
        assignment("dead fallback preserved", map, eligible, eligible);
        assignment("empty map", map, null);
        if (failures != 0) throw new AssertionError(failures + " checks failed");
        System.out.println("All controller assignment checks passed.");
    }
}
