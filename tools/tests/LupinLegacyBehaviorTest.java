import client.MapleCharacter;
import client.MapleClient;
import config.YamlConfig;
import java.awt.Point;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.server.channel.handlers.MoveLifeHandler;
import provider.MapleData;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.life.MapleMonsterInformationProvider;
import server.life.MapleMonsterStats;
import server.maps.MapleFoothold;
import server.maps.MapleFootholdTree;
import server.maps.MapleMap;
import tools.Pair;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;

/** Offline WZ and legacy-control regression. No socket, live database or channel services.
 * The attack cooldown service alone is stubbed; its WZ registration is checked separately.
 */
public class LupinLegacyBehaviorTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static class TestMap extends MapleMap {
        byte[] movement;
        TestMap() { super(100000100, 0, 1, 100000000, 1); }
        @Override public synchronized void startGroundMobSimulation() {
            throw new AssertionError("disabled configuration must not start ground authority");
        }
        @Override public void broadcastMessage(MapleCharacter source, byte[] packet, Point position) {
            movement = packet;
        }
    }

    private static class TestClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        TestClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) { packets.add(packet); }
    }

    private static class TestLupin extends MapleMonster {
        int attackPos = -99;
        boolean skill;
        TestLupin(MapleMonsterStats stats) { super(3210800, stats); }
        // The channel's MobClearSkillService is not available in this offline fixture.
        @Override public int canUseAttack(int pos, boolean isSkill) {
            attackPos = pos;
            skill = isSkill;
            return 1;
        }
        @Override public void aggroUpdatePuppetVisibility() {
            // Avoid scheduling the unrelated puppet visibility service.
        }
    }

    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
        YamlConfig.config.server.USE_LUPIN_ATTACK_TRACE =
                java.util.Arrays.asList(args).contains("trace");
        Method isolate = MonsterControllerAssignmentTest.class.getDeclaredMethod("isolateDatabase");
        isolate.setAccessible(true);
        isolate.invoke(null);

        MapleData wz = MapleDataProviderFactory.getDataProvider(new File("wz/Mob.wz"))
                .getData("3210800.img");
        MapleData attack = wz.getChildByPath("attack1");
        MapleData info = attack.getChildByPath("info");
        check(MapleDataTool.getPoint("range/sp", info).equals(new Point(-14, -29))
                        && MapleDataTool.getInt("range/r", info) == 200,
                "WZ attack origin and range");
        check(MapleDataTool.getInt("type", info) == 1
                        && MapleDataTool.getInt("attackAfter", info) == 850
                        && MapleDataTool.getInt("PADamage", info) == 125,
                "WZ attack kind, cooldown metadata and attack damage");
        check(attack.getChildByPath("ball") == null && attack.getChildByPath("bullet") == null
                        && info.getChildByPath("ball") == null && info.getChildByPath("bullet") == null,
                "no direct ball/bullet child in attack or info");
        int frameCount = 0, duration = 0;
        for (MapleData frame : attack) {
            if (!frame.getName().matches("\\d+")) continue;
            frameCount++;
            duration += MapleDataTool.getInt("delay", frame, 0);
        }
        check(frameCount == 11 && duration == 2250, "11 WZ attack frames total 2250 ms");
        check(info.getChildByPath("hit").getChildren().size() == 6, "six hit-effect frames");

        MapleMonsterStats stats = MapleLifeFactory.getMonster(3210800).getStats();
        check(stats.groundJumpOnly && stats.groundHasAttack && !stats.isBoss()
                        && stats.getSkills().isEmpty(), "production parser recognises jump and attack");
        MapleMonsterInformationProvider provider = MapleMonsterInformationProvider.getInstance();
        Pair<Integer, Integer> attackInfo = provider.getMobAttackInfo(3210800, 0);
        check(attackInfo != null && attackInfo.getLeft() == 0 && attackInfo.getRight() == 850
                        && provider.getMobAttackAnimationTime(3210800, 0) == 2250,
                "production attack info and animation registrations");

        TestMap map = new TestMap();
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-100, -100), new Point(100, 100));
        tree.insert(new MapleFoothold(new Point(-100, 0), new Point(100, 0), 1));
        map.setFootholds(tree);
        TestLupin mob = new TestLupin(stats);
        mob.setPosition(new Point(0, 0));
        mob.setFh(1);
        mob.setMap(map);
        map.addMapObject(mob);
        check(!mob.isServerGroundAuthority(), "Lupin remains legacy with authority disabled");
        Method playerFactory = MonsterControllerAssignmentTest.class.getDeclaredMethod("player",
                MapleMap.class, boolean.class, boolean.class, boolean.class, boolean.class);
        playerFactory.setAccessible(true);
        MapleCharacter player = (MapleCharacter) playerFactory.invoke(null, map, true, false, false, true);
        player.setMapTransitionComplete();
        TestClient client = new TestClient();
        player.setClient(client);
        client.setPlayer(player);
        mob.aggroSwitchController(player, false);
        check(mob.getController() == player && !mob.isControllerHasAggro(), "initial controller without aggro");
        mob.aggroMonsterDamage(player, 10);
        check(mob.getController() == player && mob.isControllerHasAggro(), "damage sets controller aggro");
        int packetsBeforeMove = client.packets.size();
        int hpBeforeMove = player.getHp();

        // Current MoveLifeHandler masks (rather than shifts) the activity:
        // 24 reaches attack position zero. This fixture documents that path,
        // not an asserted wire encoding for a working banana-throwing client.
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(mob.getObjectId()).putShort((short) 1);
        b.put((byte) 0).put((byte) 24).put((byte) 0).put((byte) 0).putShort((short) 0);
        b.putLong(0).put((byte) 0).putInt(0).putShort((short) 0).putShort((short) 2);
        b.put((byte) 1).put((byte) 0).putShort((short) 0).putShort((short) 2)
                .putShort((short) 0).putShort((short) 0).putShort((short) 1)
                .put((byte) 2).putShort((short) 1);
        check(!b.hasRemaining(), "legacy movement fixture length");
        GenericSeekableLittleEndianAccessor packet =
                new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(b.array()));
        new MoveLifeHandler().handlePacket(packet, client);
        check(mob.attackPos == 0 && !mob.skill && packet.available() == 0,
                "legacy handler sees attack position zero");
        check(client.packets.size() == packetsBeforeMove + 1, "controller receives movement acknowledgement");
        check(map.movement != null && (map.movement[8] & 0xff) == 24,
                "legacy attack activity rebroadcast to observers");
        check(mob.isControllerHasAggro() && mob.isControllerKnowsAboutAggro(),
                "controller movement consumes aggro acknowledgement");
        check(player.getHp() == hpBeforeMove, "attack movement alone does not apply player damage");
        System.out.println("PASS Lupin WZ metadata, legacy fallback, aggro and activity rebroadcast"
                + " (attack cooldown service stubbed; no projectile or damage simulation)");
    }
}
