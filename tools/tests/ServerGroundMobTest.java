import client.MapleCharacter;
import client.MapleClient;
import config.YamlConfig;
import java.awt.Point;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.lang.reflect.Field;
import net.server.channel.handlers.MoveLifeHandler;
import protocol.generated.maple.map.MobAuthority;
import protocol.generated.maple.map.MobSnapshot;
import protocol.generated.maple.network.Packet;
import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import server.life.GroundMobSimulation;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.life.SpawnPoint;
import server.maps.MapleFoothold;
import server.maps.MapleFootholdTree;
import server.maps.MapleMap;
import server.maps.MapleMapFactory;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;

/** Offline production simulation/handler test; uses real WZ maps and snail stats.
 * Reuses MonsterControllerAssignmentTest's isolated JDBC/character fixture.
 * No sockets, database, deployment or automatic map timers.
 */
public class ServerGroundMobTest {
    private static final int[] WALK_IDS = {
            100100, 100101, 120100, 100120, 100121, 1110101, 1120100, 1130100,
            2110200, 130100, 130101, 1110100, 1110130, 1140100, 1140130,
            2130100, 2130103, 2230110, 2230111, 4130100, 5130100, 4300006,
            4300009, 9300000, 9300001, 9400616, 9400618, 9400620, 9400621, 9400657
    };
    private static final int[] JUMP_IDS = {
            210100, 1210100, 1210101, 1210102, 2220100, 2230101, 1210103, 3210800,
            2230100, 2230102, 2230131, 3230100, 3230101, 4230100, 4230102,
            4230125, 4230126, 4230400, 6230100, 9300002, 9300274, 9400614,
            9400615, 9400617, 9400619, 9400622, 9400655, 9400656
    };
    private static void verifyTemplateEvidence(int[] walkIds, int[] jumpIds) throws Exception {
        MapleDataProvider mobWz =
                MapleDataProviderFactory.getDataProvider(new File("wz/Mob.wz"));
        for (int id : walkIds) {
            String fileId = String.format("%07d", id);
            MapleData mob = mobWz.getData(fileId + ".img");
            check(mob != null, "Mob.wz template " + id);
            MapleData info = mob.getChildByPath("info");
            MapleMonsterStats stats = MapleLifeFactory.getMonster(id).getStats();
            check(mob.getChildByPath("move") != null, "move template " + id);
            check(mob.getChildByPath("fly") == null, "no fly template " + id);
            check(mob.getChildByPath("jump") == null, "no jump template " + id);
            check(info.getChildByPath("boss") == null
                    || MapleDataTool.getInt(info.getChildByPath("boss"), 0) == 0, "non-boss template " + id);
            check(info.getChildByPath("skill") == null, "no skill template " + id);
            check(info.getChildByPath("link") == null, "unlinked client animation " + id);
            check(mob.getChildByPath("attack1") == null && info.getChildByPath("revive") == null,
                    "no attack or revive " + id);
            check(!stats.isBoss() && stats.getSkills().isEmpty() && stats.groundWalkOnly
                            && !stats.groundJumpOnly && !stats.groundHasAttack,
                    "production parser walk eligibility " + id);
        }
        for (int id : jumpIds) {
            MapleData mob = mobWz.getData(String.format("%07d.img", id));
            check(mob != null, "Mob.wz jump template " + id);
            MapleData info = mob.getChildByPath("info");
            MapleMonsterStats stats = MapleLifeFactory.getMonster(id).getStats();
            check(mob.getChildByPath("move") != null && mob.getChildByPath("jump") != null,
                    "move+jump template " + id);
            check(mob.getChildByPath("fly") == null, "no fly template " + id);
            check(info.getChildByPath("boss") == null
                    || MapleDataTool.getInt(info.getChildByPath("boss"), 0) == 0, "non-boss template " + id);
            check(info.getChildByPath("skill") == null, "no skill template " + id);
            check(info.getChildByPath("link") == null, "unlinked client animation " + id);
            check((id == 3210800 ? mob.getChildByPath("attack1") != null
                            : mob.getChildByPath("attack1") == null)
                            && info.getChildByPath("revive") == null,
                    "only opted-in Lupin has an attack; no revive " + id);
            check(!stats.isBoss() && stats.getSkills().isEmpty() && stats.groundJumpOnly
                            && !stats.groundWalkOnly
                            && (id == 3210800
                                ? stats.groundHasAttack && stats.groundAttackProfile != null
                                : !stats.groundHasAttack),
                    "production parser jump eligibility " + id);
        }
        for (int[] pair : new int[][]{{3400000, 9410005}, {3400001, 9410006},
                {3400002, 9410007}, {4300014, 4300010}, {4300015, 4300011},
                {4300016, 4300012}, {9100000, 210100}, {9100001, 2130103},
                {9100002, 2300100}, {3000002, 3000001}, {3000003, 3000001},
                {3230301, 3230300}}) {
            MapleData src = mobWz.getData(String.format("%07d.img", pair[0]));
            MapleData linked = mobWz.getData(String.format("%07d.img", pair[1]));
            check(MapleDataTool.getIntConvert("link", src.getChildByPath("info"), 0) == pair[1]
                            && linked != null && src.getChildByPath("move") == null
                            && src.getChildByPath("jump") == null && src.getChildByPath("fly") == null,
                    "source, not linked animations, determines client mobility " + pair[0]);
        }
    }
    // Same one-hop map link, factory life fields, tree bounds and SpawnPoint
    // copying/placement as production. One-shot initial spawns are NOT rebased.
    private static void actualSpawnFootholds() throws Exception {
        Set<Integer> supported = new HashSet<>();
        for (int id : WALK_IDS) supported.add(id);
        for (int id : JUMP_IDS) supported.add(id);
        MapleDataProvider wz = MapleDataProviderFactory.getDataProvider(new File("wz/Map.wz"));
        File[] files = new File("wz/Map.wz/Map/Map1").listFiles((dir, name) ->
                name.matches("10[0-5][0-9]{6}\\.img\\.xml"));
        check(files != null, "map XML directory");
        Arrays.sort(files);
        Method below = MapleMap.class.getDeclaredMethod("calcPointBelow", Point.class);
        below.setAccessible(true);
        Method relevant = MapleFootholdTree.class.getDeclaredMethod("getRelevants", Point.class);
        relevant.setAccessible(true);
        Method loadLife = MapleMapFactory.class.getDeclaredMethod("loadLife", int.class, String.class,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        loadLife.setAccessible(true);
        Method loadLifeRaw = MapleMapFactory.class.getDeclaredMethod("loadLifeRaw", MapleMap.class, int.class,
                String.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class);
        loadLifeRaw.setAccessible(true);
        Method allSpawnPoints = MapleMap.class.getDeclaredMethod("getAllMonsterSpawn");
        allSpawnPoints.setAccessible(true);
        int accepted = 0, rejected = 0, unresolved = 0, recovered = 0, linkedMaps = 0, oneShots = 0;
        Map<String, Integer> failures = new TreeMap<>();
        Map<Integer, Integer> recoveriesByMap = new TreeMap<>();
        Set<String> airborneCurseEyes = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        Set<Integer> landed = new HashSet<>();
        Set<Integer> jumpIds = new HashSet<>();
        for (int id : JUMP_IDS) jumpIds.add(id);
        for (File file : files) {
            int mapId = Integer.parseInt(file.getName().substring(0, 9));
            MapleData data = wz.getData("Map/Map1/" + mapId + ".img");
            String link = MapleDataTool.getString("info/link", data, "");
            if (!link.isEmpty()) {
                linkedMaps++;
                int target = Integer.parseInt(link);
                data = wz.getData("Map/Map" + target / 100000000 + "/" + target + ".img");
            }
            if (data == null || data.getChildByPath("foothold") == null || data.getChildByPath("life") == null)
                continue;
            List<MapleFoothold> footholds = new ArrayList<>();
            Point lower = new Point(), upper = new Point();
            for (MapleData layer : data.getChildByPath("foothold"))
                for (MapleData group : layer)
                    for (MapleData n : group) {
                        MapleFoothold f = new MapleFoothold(
                                new Point(MapleDataTool.getInt("x1", n, 0), MapleDataTool.getInt("y1", n, 0)),
                                new Point(MapleDataTool.getInt("x2", n, 0), MapleDataTool.getInt("y2", n, 0)),
                                Integer.parseInt(n.getName()));
                        f.setPrev(MapleDataTool.getInt("prev", n, 0));
                        f.setNext(MapleDataTool.getInt("next", n, 0));
                        lower.x = Math.min(lower.x, f.getX1());
                        upper.x = Math.max(upper.x, f.getX2());
                        lower.y = Math.min(lower.y, f.getY1());
                        upper.y = Math.max(upper.y, f.getY2());
                        footholds.add(f);
                    }
            MapleFootholdTree tree = new MapleFootholdTree(lower, upper);
            for (MapleFoothold f : footholds) tree.insert(f);
            RecordingMap map = new RecordingMap(mapId);
            map.setFootholds(tree);
            for (MapleData life : data.getChildByPath("life")) {
                if (!"m".equals(MapleDataTool.getString("type", life, ""))) continue;
                int id = Integer.parseInt(MapleDataTool.getString("id", life, "0"));
                if (!supported.contains(id)) continue;
                seen.add(id);
                int fh = MapleDataTool.getInt("fh", life, 0);
                Point pos = new Point(MapleDataTool.getInt("x", life, 0), MapleDataTool.getInt("y", life, 0));
                Point raw = new Point(pos);
                int mobTime = MapleDataTool.getInt("mobTime", life, 0);
                boolean oneShot = mobTime == -1;
                if (oneShot) oneShots++;
                int facing = MapleDataTool.getInt("f", life, 0);
                int hide = MapleDataTool.getInt("hide", life, 0);
                MapleMonster original = (MapleMonster) loadLife.invoke(null, id, "m",
                        MapleDataTool.getInt(life.getChildByPath("cy")), facing, fh,
                        MapleDataTool.getInt(life.getChildByPath("rx0")),
                        MapleDataTool.getInt(life.getChildByPath("rx1")), raw.x, raw.y, hide);
                MapleFoothold spawnBelow = tree.findBelow(raw);
                if ((mapId == 101030105 && life.getName().equals("15"))
                        || (mapId == 103040300 && life.getName().equals("22"))) {
                    @SuppressWarnings("unchecked")
                    List<MapleFoothold> candidates = (List<MapleFoothold>) relevant.invoke(tree, raw);
                    boolean sourceConsidered = candidates.contains(tree.getById(fh));
                    candidates.removeIf(f -> f.isWall() || raw.x < f.getX1() || raw.x > f.getX2());
                    java.util.Collections.sort(candidates);
                    List<String> order = new ArrayList<>();
                    for (MapleFoothold f : candidates)
                        order.add(f.getId() + "[" + f.getY1() + "," + f.getY2() + "]");
                    System.out.println("Legacy rebasing " + id + "@" + mapId + " life=" + life.getName()
                            + ": WZ fh=" + fh + ", source-fh-considered=" + sourceConsidered
                            + ", selected=" + (spawnBelow == null ? "none" : spawnBelow.getId())
                            + ", legacy-sort=" + order);
                }
                MapleMonster mob = original;
                if (!oneShot) {
                    pos = (Point) below.invoke(map, pos);
                    if (pos == null) {
                        unresolved++;
                        failures.merge(id + "@" + mapId + ":no-ground-below", 1, Integer::sum);
                        continue;
                    }
                    pos.y--;
                    int team = MapleDataTool.getInt("team", life, -1);
                    if (map.isCPQMap2()) team = Integer.parseInt(life.getName()) % 2 == 0 ? 0 : 1;
                    mob = new SpawnPoint(original, pos, !original.isMobile(), mobTime, 0, team).getMonster();
                    check(!mob.isFake() && !mob.isHidden() && mob.getFh() == fh
                                    && mob.getPosition().equals(pos),
                            "production SpawnPoint copies original fh and rebased position");
                } else {
                    check(!mob.isFake() && mob.isHidden() == (hide == 1) && mob.getPosition().equals(raw),
                            "one-shot WZ spawn is real and initially unre-based");
                }
                GroundMobSimulation strict = GroundMobSimulation.create(id, mob.getStats(), tree, fh, pos, facing == 1);
                GroundMobSimulation sim = oneShot ? strict
                        : GroundMobSimulation.createAtAttachment(id, mob.getStats(), tree, fh, pos, facing == 1);
                YamlConfig.config.server.USE_SERVER_GROUND_MOBS = true;
                mob.setMap(map);
                check(mob.isServerGroundAuthority() == (sim != null), "actual authority attach " + id);
                if (strict == null) {
                    // Exercise the real factory's loadLifeRaw -> spawnMonster /
                    // addMonsterSpawn -> addAllMonsterSpawn on each formerly rejected entry.
                    RecordingMap integration = new RecordingMap(mapId);
                    integration.setFootholds(tree);
                    int team = MapleDataTool.getInt("team", life, -1);
                    if (integration.isCPQMap2()) team = Integer.parseInt(life.getName()) % 2 == 0 ? 0 : 1;
                    loadLifeRaw.invoke(null, integration, id, "m",
                            MapleDataTool.getInt(life.getChildByPath("cy")), facing, fh,
                            MapleDataTool.getInt(life.getChildByPath("rx0")),
                            MapleDataTool.getInt(life.getChildByPath("rx1")), raw.x, raw.y, hide, mobTime, team);
                    MapleMonster produced = integration.getMonsterById(id);
                    check(produced != null && produced.isServerGroundAuthority() == mob.isServerGroundAuthority()
                                    && produced.getFh() == mob.getFh() && produced.getPosition().equals(mob.getPosition()),
                            "actual factory spawn matches fixture " + id + "@" + mapId);
                    RecordingMap legacyMap = new RecordingMap(mapId);
                    legacyMap.setFootholds(tree);
                    YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
                    loadLifeRaw.invoke(null, legacyMap, id, "m",
                            MapleDataTool.getInt(life.getChildByPath("cy")), facing, fh,
                            MapleDataTool.getInt(life.getChildByPath("rx0")),
                            MapleDataTool.getInt(life.getChildByPath("rx1")), raw.x, raw.y, hide, mobTime, team);
                    MapleMonster producedLegacy = legacyMap.getMonsterById(id);
                    check(producedLegacy != null && !producedLegacy.isServerGroundAuthority()
                                    && producedLegacy.getFh() == fh && producedLegacy.getPosition().equals(pos),
                            "disabled real factory preserves legacy fh and placement");
                    YamlConfig.config.server.USE_SERVER_GROUND_MOBS = true;
                    @SuppressWarnings("unchecked")
                    List<SpawnPoint> all = (List<SpawnPoint>) allSpawnPoints.invoke(integration);
                    Point resetPos = (Point) below.invoke(integration, raw);
                    check(resetPos != null && all.size() == 1, "factory registers reset spawn");
                    resetPos.y--;
                    check(all.get(0).getFh() == fh && all.get(0).getPosition().equals(resetPos),
                            "factory reset keeps original fh and rebases raw position");
                }
                if (strict != null) {
                    check(sim != null && sim.foothold() == strict.foothold()
                                    && sim.position().equals(strict.position()), "unchanged previously eligible attachment");
                } else if (sim != null) {
                    recovered++;
                    recoveriesByMap.merge(mapId, 1, Integer::sum);
                    check(!oneShot && spawnBelow != null && sim.foothold() == spawnBelow.getId(),
                            "recovered actual rebased foothold " + id + "@" + mapId);
                    check(mob.getFh() == sim.foothold(), "recovered fh installed at attachment");
                    RecordingClient client = new RecordingClient();
                    mob.sendSpawnData(client);
                    MobSnapshot state = snapshot(client.packets.get(1));
                    check(state.authority() == MobAuthority.ServerGround && state.foothold() == mob.getFh()
                                    && state.x() == mob.getPosition().x && state.y() == mob.getPosition().y,
                            "first snapshot carries attached foothold and position");
                    ByteBuffer legacy = ByteBuffer.wrap(client.packets.get(0)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    check(Short.toUnsignedInt(legacy.getShort(34)) == mob.getFh(),
                            "legacy spawn carries attached foothold");
                }
                if (sim == null) {
                    rejected++;
                    MapleFoothold sourceFh = tree.getById(fh);
                    String reason;
                    if (sourceFh == null) reason = "missing-fh";
                    else if (sourceFh.isWall()) reason = "wall-fh";
                    else if (pos.x < sourceFh.getX1() || pos.x > sourceFh.getX2()) reason = "x-outside-fh";
                    else {
                        double y = sourceFh.getY1() + (pos.x - sourceFh.getX1())
                                * (sourceFh.getY2() - sourceFh.getY1())
                                / (double) (sourceFh.getX2() - sourceFh.getX1());
                        check(Math.abs(y - pos.y) > 8, "unexpected non-geometry rejection " + id);
                        reason = "y-over-8px";
                    }
                    failures.merge(id + "@" + mapId + ":" + reason, 1, Integer::sum);
                    if (!oneShot) {
                        MapleFoothold nearGround = tree.findGroundForAttachment(pos, 2.0);
                        MapleFoothold legacyBelow = tree.findBelow(pos);
                        System.out.println("Unresolved rebased spawn " + id + "@" + mapId + " life="
                                + life.getName() + " pos=" + pos + " unique="
                                + (nearGround == null ? "none" : nearGround.getId()) + " legacyBelow="
                                + (legacyBelow == null ? "none" : legacyBelow.getId()));
                    }
                    if (mapId == 103000804) {
                        check(oneShot && spawnBelow != null && spawnBelow.getId() == fh,
                                "one-shot airborne spawn retains correct WZ fh");
                        if (id == 9300002) {
                            int expectedFh = Map.of("0", 76, "2", 80, "1", 81).get(life.getName());
                            check(fh == expectedFh && raw.y < -2175 && -2175 - raw.y > 8,
                                    "exact airborne Curse Eye WZ foothold");
                            airborneCurseEyes.add(life.getName());
                        }
                        // Factory also registers a reset SpawnPoint after the direct
                        // one-shot spawn. That later spawn is rebased, not fake.
                        Point resetPos = (Point) below.invoke(map, mob.getPosition());
                        check(resetPos != null, "one-shot reset ground exists");
                        resetPos.y--;
                        MapleMonster reset = new SpawnPoint(mob, resetPos, !mob.isMobile(), mobTime, 0, -1).getMonster();
                        check(!reset.isFake() && reset.getFh() == fh && reset.getPosition().equals(resetPos),
                                "one-shot reset uses rebased SpawnPoint");
                        reset.setMap(map);
                        check(reset.isServerGroundAuthority() && reset.getFh() == fh,
                                "one-shot reset attaches at its grounded original fh");
                    }
                } else {
                    accepted++;
                    for (int tick = 0; tick < 10; tick++) sim.tick(false, 0);
                    if (jumpIds.contains(id) && (!landed.contains(id) || strict == null)) {
                        set(sim, "decisionTicks", 10000);
                        set(sim, "jumping", true);
                        set(sim, "launch", true);
                        boolean airborne = false;
                        for (int tick = 0; tick < 30; tick++) {
                            sim.tick(false, 0);
                            airborne |= (sim.stance() & 0xFE) == 6;
                        }
                        MapleFoothold landing = tree.getById(sim.foothold());
                        Point p = sim.position();
                        check(airborne && (sim.stance() & 0xFE) != 6 && landing != null
                                        && !landing.isWall() && p.x >= landing.getX1() && p.x <= landing.getX2(),
                                "actual-spawn jump landing " + id + " map " + mapId);
                        double y = landing.getY1() + (p.x - landing.getX1())
                                * (landing.getY2() - landing.getY1())
                                / (double) (landing.getX2() - landing.getX1());
                        check(Math.abs(p.y - y) <= 2, "actual-spawn grounded landing " + id);
                        landed.add(id);
                    }
                }
            }
        }
        check(accepted > 0 && !seen.isEmpty(), "actual eligible WZ spawns");
        check(airborneCurseEyes.equals(new HashSet<>(Arrays.asList("0", "1", "2"))),
                "all three airborne 9300002 initial spawns checked");
        check(recoveriesByMap.equals(Map.of(101030101, 10, 101030105, 1,
                        103040300, 1, 105090310, 15)),
                "all 27 previously rejected rebased WZ spawns recovered");
        Set<Integer> missingLandings = new HashSet<>(jumpIds);
        missingLandings.removeAll(landed);
        System.out.println("Actual 100-105 WZ spawn entries: accepted=" + accepted + ", rejected="
                + rejected + ", no-ground=" + unresolved + ", ids=" + seen.size()
                + ", recovered=" + recovered + ", linked-maps=" + linkedMaps + ", one-shot=" + oneShots
                + ", jump-ids-landed=" + landed.size() + ", no-eligible-jump-spawn=" + missingLandings);
        if (!failures.isEmpty()) System.out.println("Actual spawn failures (id@map:reason=count): " + failures);
    }
    private static void set(GroundMobSimulation s, String name, Object value) throws Exception {
        Field field = GroundMobSimulation.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(s, value);
    }
    private static double number(GroundMobSimulation s, String name) throws Exception {
        Field field = GroundMobSimulation.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(s);
    }
    private static GroundMobSimulation jumpFixture(int id, MapleFootholdTree tree,
                                                   MapleFoothold ground) throws Exception {
        server.life.MapleMonsterStats stats = MapleLifeFactory.getMonster(id).getStats();
        GroundMobSimulation s = GroundMobSimulation.create(id, stats, tree, ground.getId(),
                new Point(0, ground.getY1()), false);
        check(s != null, "jump template " + id);
        set(s, "horizontalSpeed", 0.5);
        set(s, "jumping", true);
        set(s, "launch", true);
        set(s, "decisionTicks", 10000);
        return s;
    }
    private static void jumpParity(List<double[]> nativeFrames) throws Exception {
        MapleFoothold ground = new MapleFoothold(new Point(-1000, 0), new Point(1000, 0), 1);
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(ground);
        Method step = GroundMobSimulation.class.getDeclaredMethod("stepJumpPhysics", double.class, int.class);
        step.setAccessible(true);
        for (int id : new int[]{210100, 1210100, 1210101, 1210102, 2220100, 2230101, 1210103}) {
            GroundMobSimulation s = jumpFixture(id, tree, ground);
            double expectedX = 0, expectedY = 0, v = 0, apex = 0;
            int contact = 0;
            for (int frame = 1; frame <= 73; frame++) {
                v = frame == 1 ? -5.0 : v + 0.14;
                expectedX += 0.4; // 0.5 - (0.5 + 0.1) * (0.5 / 3), then airborne coast.
                expectedY += v;
                if (expectedY >= 0) { expectedY = 0; v = 0; contact = frame; }
                step.invoke(s, 0.0, 0);
                apex = Math.min(apex, number(s, "y"));
                check(Math.abs(number(s, "y") - expectedY) < 1e-9, "legacy y frame " + frame);
                check(Math.abs(number(s, "x") - expectedX) < 1e-9, "legacy x frame " + frame);
                check(Math.abs(number(s, "verticalSpeed") - v) < 1e-9, "legacy velocity frame " + frame);
                if (!nativeFrames.isEmpty()) {
                    double[] nativeFrame = nativeFrames.get(frame - 1);
                    check(nativeFrame[0] == frame
                            && Math.abs(number(s, "x") - nativeFrame[1]) < 1e-9
                            && Math.abs(number(s, "y") - nativeFrame[2]) < 1e-9
                            && Math.abs(number(s, "horizontalSpeed") - nativeFrame[3]) < 1e-9
                            && Math.abs(number(s, "verticalSpeed") - nativeFrame[4]) < 1e-9,
                            "production C++ Physics trajectory " + id + " frame " + frame);
                }
            }
            check(contact * 8 == 584 && Math.abs(apex + 91.8) < 1e-9, "apex/airtime");
            check((s.stance() & 0xFE) != 6 && s.position().y == 0, "landing ends jump");
            // Real 100 ms publication advances 12/13 legacy updates, never 100 ms gravity.
            s = jumpFixture(id, tree, ground);
            for (int tick = 1; tick <= 5; tick++) {
                s.tick(false, 0);
                int frame = tick * 100 / 8;
                double y = -5 * frame + 0.07 * (frame - 1) * frame;
                check(Math.abs(number(s, "y") - y) < 1e-9, "snapshot fixed-step accumulator");
                MobSnapshot wire = snapshot(tools.packets.map.MobSnapshotPackets.snapshot(
                        100000100, id, 1, tick, true, s.position(), s.foothold(), s.stance(), true));
                check(wire.y() == s.position().y && wire.intervalMs() == 100
                        && (wire.stance() & 0xFE) == 6, "jump snapshot position/interval/stance");
            }
            s.tick(false, 0);
            check(s.position().y == 0 && (s.stance() & 0xFE) != 6, "landing snapshot at 600 ms");
            // Pause must not consume the launch or accumulate a catch-up jump.
            s = jumpFixture(id, tree, ground);
            s.tick(true, 0);
            check(s.position().equals(new Point(0, 0)), "frozen launch");
            s.tick(false, 0);
            check(Math.abs(number(s, "y") - (-54.08)) < 1e-9, "resume uses retained timestep phase");
            s = jumpFixture(id, tree, ground);
            set(s, "horizontalSpeed", -0.5);
            set(s, "direction", -1);
            for (int frame = 1; frame <= 73; frame++) step.invoke(s, 0.0, -100);
            check(Math.abs(number(s, "x") + 29.2) < 1e-9 && s.position().y == 0,
                    "leftward coast ignores walking speed modifier in flight");
            s = jumpFixture(id, tree, ground);
            set(s, "horizontalSpeed", 0.05);
            step.invoke(s, 0.0, 0);
            check(number(s, "horizontalSpeed") == 0 && number(s, "y") == -5,
                    "legacy near-zero launch friction/no launch gravity");
        }
        // Higher platform: cross from ABOVE while descending, not merely findBelow at apex.
        MapleFoothold upper = new MapleFoothold(new Point(-1000, -40), new Point(1000, -40), 2);
        tree.insert(upper);
        GroundMobSimulation s = jumpFixture(210100, tree, ground);
        for (int frame = 1; frame <= 73 && s.foothold() != 2; frame++) step.invoke(s, 0.0, 0);
        check(s.foothold() == 2 && s.position().y == -40, "swept higher-platform landing");
        // A slope must not bend the airborne trajectory or shift its apex.
        MapleFoothold slope = new MapleFoothold(new Point(-1000, -100), new Point(1000, 100), 3);
        MapleFootholdTree slopes = new MapleFootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        slopes.insert(slope);
        s = GroundMobSimulation.create(210100, MapleLifeFactory.getMonster(210100).getStats(),
                slopes, 3, new Point(0, 0), false);
        check(s != null, "sloped launch fixture");
        set(s, "horizontalSpeed", 0.5);
        set(s, "jumping", true);
        set(s, "launch", true);
        step.invoke(s, 0.0, 0);
        for (int frame = 2; frame <= 36; frame++) step.invoke(s, 0.0, 0);
        check(Math.abs(number(s, "y") + 91.8) < 1e-9, "slope-independent apex");
        // A landing step that also crosses a foothold join must land. Selecting the
        // surface at the pre-step column missed this landing, dropped the feet below
        // the floor and let the mob fall (and overflow int16 coordinates) forever.
        MapleFoothold joinLeft = new MapleFoothold(new Point(-1000, 0), new Point(0, 0), 4);
        MapleFoothold joinRight = new MapleFoothold(new Point(0, 0), new Point(1000, 0), 5);
        joinLeft.setNext(5);
        joinRight.setPrev(4);
        MapleFootholdTree joined = new MapleFootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        joined.insert(joinLeft);
        joined.insert(joinRight);
        s = GroundMobSimulation.create(1210100, MapleLifeFactory.getMonster(1210100).getStats(),
                joined, 4, new Point(-1, 0), false);
        check(s != null, "join landing fixture");
        set(s, "jumping", true);
        set(s, "launch", false);
        set(s, "decisionTicks", 10000);
        set(s, "x", -0.2);
        set(s, "y", -4.0);
        set(s, "horizontalSpeed", 0.5);
        set(s, "verticalSpeed", 4.9);
        step.invoke(s, 0.0, 0);
        check(number(s, "y") == 0 && s.position().y == 0 && (s.stance() & 0xFE) != 6,
                "join-crossing landing step");
        // A mob whose feet somehow ended up below every surface recovers by landing
        // on its walk-chain foothold instead of accelerating off the map.
        s = GroundMobSimulation.create(1210100, MapleLifeFactory.getMonster(1210100).getStats(),
                joined, 4, new Point(-50, 0), false);
        check(s != null, "tunnel recovery fixture");
        set(s, "jumping", true);
        set(s, "launch", false);
        set(s, "decisionTicks", 10000);
        set(s, "x", -50.0);
        set(s, "y", 5.0);
        set(s, "horizontalSpeed", 0.0);
        set(s, "verticalSpeed", 3.0);
        step.invoke(s, 0.0, 0);
        check(number(s, "y") == 0 && (s.stance() & 0xFE) != 6, "tunneled fall recovery");
        System.out.println("PASS jump parity: 7 templates, 73 updates, apex 91.8 px, contact 584 ms,"
                + " publication 600 ms; native frames=" + nativeFrames.size()
                + "; freeze/resume, slopes, higher-platform crossing");
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
    private static class RecordingMap extends MapleMap {
        byte[] last;
        int starts;
        RecordingMap(int id) { super(id, 0, 1, 100000000, 1); }
        @Override public synchronized void startGroundMobSimulation() { starts++; }
        @Override public void broadcastMessage(byte[] data) { last = data; }
    }
    private static class RecordingClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        RecordingClient() { super(null, null, null); }
        @Override public void announce(byte[] data) { packets.add(data); }
    }
    private static MobSnapshot snapshot(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        check(Packet.PacketBufferHasIdentifier(buffer), "MSFB, no opcode");
        return (MobSnapshot) Packet.getRootAsPacket(buffer).serverMessage(new MobSnapshot());
    }
    private static MapleMonster spawn(RecordingMap map, int id, MapleFoothold f, boolean enabled) {
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = enabled;
        MapleMonster mob = MapleLifeFactory.getMonster(id);
        int x = (f.getX1() + f.getX2()) / 2;
        int y = (int) Math.round(f.getY1() + (x - f.getX1())
                * (f.getY2() - f.getY1()) / (double) (f.getX2() - f.getX1()));
        mob.setFh(f.getId());
        mob.setPosition(new Point(x, y));
        mob.setMap(map);
        map.addMapObject(mob);
        return mob;
    }
    private static void attachmentRecoveryAndFake() {
        MapleFootholdTree tree = new MapleFootholdTree(new Point(-100, -100), new Point(200, 200));
        MapleFoothold ground = new MapleFoothold(new Point(0, 100), new Point(100, 100), 7);
        tree.insert(ground);
        RecordingMap map = new RecordingMap(100000100);
        map.setFootholds(tree);
        Point near = new Point(50, 99);
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = false;
        MapleMonster disabled = new SpawnPoint(MapleLifeFactory.getMonster(120100), near, false, 0, 0, -1).getMonster();
        map.spawnMonster(disabled);
        check(!disabled.isServerGroundAuthority() && disabled.getFh() == 0
                        && disabled.getPosition().equals(near),
                "disabled attachment leaves legacy position and foothold untouched");
        YamlConfig.config.server.USE_SERVER_GROUND_MOBS = true;
        MapleMonster fake = new SpawnPoint(MapleLifeFactory.getMonster(120100), near, false, 0, 0, -1).getMonster();
        map.spawnFakeMonster(fake);
        check(fake.isFake() && !fake.isServerGroundAuthority() && fake.getFh() == 0
                        && fake.getPosition().equals(near), "actual fake spawn cannot recover authority");
        RecordingClient client = new RecordingClient();
        fake.sendSpawnData(client);
        check(snapshot(client.packets.get(1)).authority() == MobAuthority.Legacy,
                "fake spawn announces legacy");
        map.makeMonsterReal(fake);
        check(!fake.isFake() && !fake.isServerGroundAuthority() && fake.getFh() == 0
                        && fake.getPosition().equals(near), "make-real keeps the latched legacy path");

        MapleMonsterStats stats = MapleLifeFactory.getMonster(120100).getStats();
        check(GroundMobSimulation.createAtAttachment(120100, stats, tree, 0, near, false).foothold() == 7,
                "unique near-ground stale fh recovered");
        check(GroundMobSimulation.createAtAttachment(120100, stats, tree, 0, new Point(50, 97), false) == null,
                "airborne or distant spawn stays legacy");
        MapleFootholdTree ambiguous = new MapleFootholdTree(new Point(-100, -100), new Point(200, 200));
        ambiguous.insert(ground);
        ambiguous.insert(new MapleFoothold(new Point(0, 101), new Point(100, 101), 8));
        check(GroundMobSimulation.createAtAttachment(120100, stats, ambiguous, 0, near, false) == null,
                "overlapping nearby floors stay legacy");
        check(GroundMobSimulation.createAtAttachment(120100, stats, ambiguous, 7, near, false).foothold() == 7,
                "previously valid fh wins even at ambiguous overlaps");
        MapleFootholdTree join = new MapleFootholdTree(new Point(-100, -100), new Point(200, 200));
        MapleFoothold left = new MapleFoothold(new Point(-50, 0), new Point(0, 0), 11);
        MapleFoothold right = new MapleFoothold(new Point(0, 0), new Point(50, 0), 12);
        left.setNext(12);
        right.setPrev(11);
        join.insert(left);
        join.insert(right);
        GroundMobSimulation atJoin = GroundMobSimulation.createAtAttachment(120100, stats, join, 0, new Point(0, -1), false);
        check(atJoin != null && atJoin.foothold() == join.findBelow(new Point(0, -1)).getId(),
                "connected endpoint uses legacy side");
        atJoin.tick(false, 0);
        check(atJoin.foothold() == 12 && atJoin.position().x > 0, "recovered join traverses into neighbor");
        right.setPrev(0);
        check(GroundMobSimulation.createAtAttachment(120100, stats, join, 0, new Point(0, -1), false) == null,
                "non-reciprocal join remains legacy");
        RecordingMap realMap = new RecordingMap(100000100);
        realMap.setFootholds(tree);
        MapleMonster real = new SpawnPoint(MapleLifeFactory.getMonster(120100), near, false, 0, 0, -1).getMonster();
        realMap.spawnMonster(real);
        check(real.isServerGroundAuthority() && real.getFh() == 7
                        && real.getPosition().equals(new Point(50, 100)),
                "actual real spawn attaches recovered fh before broadcast");
        MapleMonster direct = MapleLifeFactory.getMonster(120100);
        direct.setPosition(near);
        direct.setFh(0);
        direct.setMap(realMap);
        check(!direct.isServerGroundAuthority() && direct.getPosition().equals(near) && direct.getFh() == 0,
                "non-SpawnPoint attachments do not acquire new authority");
        MapleFootholdTree effectTree = new MapleFootholdTree(new Point(-100, -100), new Point(100, 100));
        effectTree.insert(new MapleFoothold(new Point(-100, 0), new Point(100, 0), 9));
        RecordingMap effectMap = new RecordingMap(100000100);
        effectMap.setFootholds(effectTree);
        MapleMonster effect = MapleLifeFactory.getMonster(120100);
        effect.setPosition(new Point(0, 0));
        effect.setFh(0);
        effectMap.spawnMonsterWithEffect(effect, 1, new Point(30, -30));
        check(!effect.isServerGroundAuthority() && effect.getPosition().equals(new Point(30, -1)),
                "effect spawn attaches before placement and remains legacy");
    }
    public static void main(String[] args) throws Exception {
        List<double[]> nativeFrames = new ArrayList<>();
        if (args.length == 1 && args[0].equals("--legacy-trajectory")) {
            Scanner input = new Scanner(System.in);
            while (input.hasNextLine()) {
                String line = input.nextLine();
                if (!line.startsWith("LEGACY ")) continue;
                String[] parts = line.substring(7).split(" ");
                double[] values = new double[5];
                for (int i = 0; i < values.length; i++) values[i] = Double.parseDouble(parts[i]);
                nativeFrames.add(values);
            }
            check(nativeFrames.size() == 73, "complete native Physics trajectory");
        }
        verifyTemplateEvidence(WALK_IDS, JUMP_IDS);
        jumpParity(nativeFrames);
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        Method isolate = MonsterControllerAssignmentTest.class.getDeclaredMethod("isolateDatabase");
        isolate.setAccessible(true);
        isolate.invoke(null);
        attachmentRecoveryAndFake();
        actualSpawnFootholds();
        Method playerFactory = MonsterControllerAssignmentTest.class.getDeclaredMethod("player",
                MapleMap.class, boolean.class, boolean.class, boolean.class, boolean.class);
        playerFactory.setAccessible(true);
        int joins = 0, cases = 0;
        for (int mapId : new int[]{100000100, 104000000}) {
            MapleData data = MapleDataProviderFactory.getDataProvider(new File("wz/Map.wz"))
                    .getData("Map/Map" + mapId / 100000000 + "/" + mapId + ".img");
            MapleFootholdTree tree = new MapleFootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
            List<MapleFoothold> grounds = new ArrayList<>();
            for (MapleData layer : data.getChildByPath("foothold"))
                for (MapleData group : layer)
                    for (MapleData n : group) {
                        MapleFoothold f = new MapleFoothold(
                                new Point(MapleDataTool.getInt(n.getChildByPath("x1")), MapleDataTool.getInt(n.getChildByPath("y1"))),
                                new Point(MapleDataTool.getInt(n.getChildByPath("x2")), MapleDataTool.getInt(n.getChildByPath("y2"))),
                                Integer.parseInt(n.getName()));
                        f.setPrev(MapleDataTool.getInt(n.getChildByPath("prev")));
                        f.setNext(MapleDataTool.getInt(n.getChildByPath("next")));
                        tree.insert(f);
                        if (!f.isWall() && f.getX2() - f.getX1() > 20) grounds.add(f);
                    }
            RecordingMap map = new RecordingMap(mapId);
            map.setFootholds(tree);
            // Actual runtime tick, including accepted-hit knockback, traverses real
            // joins/slopes and clamps at walls/ledges. No MOVE_LIFE physics adapter.
            for (MapleFoothold f : grounds) {
                MapleMonster mob = spawn(map, 120100, f, true);
                check(mob.isServerGroundAuthority(), "real blue snail eligible");
                int oldFh = mob.getFh();
                boolean moved = false;
                for (int tick = 0; tick < 35; tick++) {
                    Point before = mob.getPosition();
                    mob.tickGroundAuthority();
                    Point after = mob.getPosition();
                    moved |= !after.equals(before);
                    MapleFoothold ground = tree.getById(mob.getFh());
                    double y = ground.getY1() + (after.x - ground.getX1())
                            * (ground.getY2() - ground.getY1()) / (double) (ground.getX2() - ground.getX1());
                    check(Math.abs(after.y - y) <= 1 && after.x >= ground.getX1()
                            && after.x <= ground.getX2(), "grounded on real foothold");
                    check(Math.abs(after.x - before.x) <= 12 && Math.abs(after.y - before.y) <= 40, "bounded tick");
                    if (mob.getFh() != oldFh) joins++;
                    oldFh = mob.getFh();
                }
                check(moved, "walking decision");
                map.removeMapObject(mob);
                mob.disposeMapObject();
                Point death = mob.getPosition();
                mob.tickGroundAuthority();
                check(death.equals(mob.getPosition()) && !snapshot(map.last).alive(), "disposal stops simulation");
                cases++;
            }
            MapleFoothold f = grounds.get(0);
            MapleMonster legacy = spawn(map, 120100, f, false);
            check(!legacy.isServerGroundAuthority(), "default/config legacy gate");
            for (int id : new int[]{4230101, 4300007, 3400003, 3400000,
                    4300014, 9100000, 3000002, 2300100, 6130101}) {
                check(!spawn(map, id, f, true).isServerGroundAuthority(),
                        "attack, revive, linked, flying or boss remains legacy " + id);
            }
            MapleMonsterStats lupinStats = MapleLifeFactory.getMonster(3210800).getStats();
            check(lupinStats.groundAttackProfile != null,
                    "validated Lupin attack profile is loaded");
            check(spawn(map, 3210800, f, true).hasServerGroundAttack(),
                    "enabled Lupin attaches movement and projectile attack together");
            check(!spawn(map, 3210800, f, false).isServerGroundAuthority(),
                    "disabled config preserves Lupin legacy fallback");
            boolean hadAttack = lupinStats.groundHasAttack;
            server.life.GroundMobAttackProfile profile = lupinStats.groundAttackProfile;
            try {
                lupinStats.groundHasAttack = false;
                check(GroundMobSimulation.create(3210800, lupinStats, map.getFootholds(),
                       f.getId(), new Point(f.getX1(), f.getY1()), false) == null,
                       "Lupin stays excluded if WZ attack metadata disappears");
                lupinStats.groundHasAttack = true;
                lupinStats.groundAttackProfile = null;
                check(GroundMobSimulation.create(3210800, lupinStats, map.getFootholds(),
                       f.getId(), new Point(f.getX1(), f.getY1()), false) == null,
                       "Lupin stays excluded if its WZ attack profile is invalid or missing");
            } finally {
                lupinStats.groundHasAttack = hadAttack;
                lupinStats.groundAttackProfile = profile;
            }
            MapleMonsterStats invalidJump = new MapleMonsterStats();
            int gx = (f.getX1() + f.getX2()) / 2;
            int gy = (int) Math.round(f.getY1() + (gx - f.getX1())
                    * (f.getY2() - f.getY1()) / (double) (f.getX2() - f.getX1()));
            Point validPosition = new Point(gx, gy);
            invalidJump.groundJumpOnly = true;
            check(GroundMobSimulation.create(210100, invalidJump, tree, f.getId(), validPosition, false) != null,
                    "guard fixture uses valid jump mode and geometry");
            invalidJump.groundJumpOnly = false; // e.g. original template also has fly
            check(GroundMobSimulation.create(210100, invalidJump, tree, f.getId(),
                    validPosition, false) == null,
                    "jump allowlist cannot bypass movement-type guard");
            MapleMonsterStats invalidWalk = new MapleMonsterStats();
            invalidWalk.groundWalkOnly = true;
            check(GroundMobSimulation.create(120100, invalidWalk, tree, f.getId(), validPosition, false) != null,
                    "guard fixture uses valid walk mode and geometry");
            invalidWalk.groundHasAttack = true;
            check(GroundMobSimulation.create(120100, invalidWalk, tree, f.getId(), validPosition, false) == null,
                    "attack action cannot bypass eligibility");
            invalidWalk.groundHasAttack = false;
            invalidWalk.setRevives(Arrays.asList(100100));
            check(GroundMobSimulation.create(120100, invalidWalk, tree, f.getId(), validPosition, false) == null,
                    "revive cannot bypass eligibility");
            for (int id : new int[]{100100, 100101, 120100}) {
                MapleMonster mob = spawn(map, id, f, true);
                check(mob.isServerGroundAuthority(), "existing supported mob " + id);
                RecordingClient client = new RecordingClient();
                mob.sendSpawnData(client);
                check(client.packets.size() == 2 && snapshot(client.packets.get(1)).authority()
                        == MobAuthority.ServerGround, "login/visibility authority follows spawn");
                Method control = MapleMonster.class.getDeclaredMethod("aggroMonsterControl",
                        MapleClient.class, MapleMonster.class, boolean.class);
                control.setAccessible(true);
                control.invoke(null, client, mob, true);
                check(client.packets.size() == 4 && snapshot(client.packets.get(3)).instance()
                        == snapshot(client.packets.get(1)).instance(), "controller update retains authority instance");
                MapleCharacter player = (MapleCharacter) playerFactory.invoke(null, map, true, false, false, true);
                player.setMapTransitionComplete();
                player.setPosition(new Point(mob.getPosition().x - 20, mob.getPosition().y));
                player.setClient(client);
                client.setPlayer(player);
                Point before = mob.getPosition();
                // Deliberately truncated after object/move id: authority check must
                // reject before trusting/parsing any coordinates or movement body.
                byte[] movement = ByteBuffer.allocate(6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(mob.getObjectId()).putShort((short) 1).array();
                new MoveLifeHandler().handlePacket(new GenericSeekableLittleEndianAccessor(
                        new ByteArrayByteStream(movement)), client);
                check(before.equals(mob.getPosition()), "reject client coordinates");
                mob.damage(player, 1, false);
                mob.tickGroundAuthority();
                check(mob.getPosition().x >= before.x && snapshot(map.last).stance() >= 8, "accepted hit server knockback");
                mob.damage(player, mob.getHp(), false);
                Point death = mob.getPosition();
                check(!snapshot(map.last).alive(), "lethal terminal snapshot before legacy death");
                mob.tickGroundAuthority();
                check(death.equals(mob.getPosition()), "late tick cannot move dead mob/drop origin");
                MapleMonster respawn = spawn(map, id, f, true);
                respawn.sendSpawnData(client);
                check(snapshot(client.packets.get(client.packets.size() - 1)).instance()
                        > snapshot(map.last).instance(), "respawn new instance");
            }
            for (int id : java.util.stream.IntStream.concat(
                    Arrays.stream(WALK_IDS), Arrays.stream(JUMP_IDS)).toArray()) {
                MapleMonster mob = spawn(map, id, f, true);
                check(mob.isServerGroundAuthority(), "new supported mob " + id);
                RecordingClient client = new RecordingClient();
                mob.sendSpawnData(client);
                check(snapshot(client.packets.get(1)).authority() == MobAuthority.ServerGround,
                        "visibility announces new authority " + id);
                if (Arrays.stream(JUMP_IDS).anyMatch(jumpId -> jumpId == id)) {
                    boolean jumped = false;
                    for (int tick = 0; tick < 1200 && !jumped; tick++) {
                        mob.tickGroundAuthority();
                        jumped = (snapshot(map.last).stance() & 0xFE) == 6;
                    }
                    check(jumped, "server publishes jump stance " + id);
                }
            }
        }
        check(joins > 0 && cases > 0, "real connected foothold traversal");
        // Exercise the actual existing scheduler and production map disposal on
        // an empty map, without starting a channel, database or network session.
        server.TimerManager.getInstance().start();
        try {
            MapleMap empty = new MapleMap(100000100, 0, 1, 100000000, 1);
            empty.startGroundMobSimulation();
            java.lang.reflect.Field taskField = MapleMap.class.getDeclaredField("groundMobTask");
            taskField.setAccessible(true);
            java.util.concurrent.ScheduledFuture<?> task =
                    (java.util.concurrent.ScheduledFuture<?>) taskField.get(empty);
            check(task != null && !task.isCancelled(), "map owns scheduler");
            empty.dispose();
            check(task.isCancelled() && taskField.get(empty) == null, "map shutdown cancels scheduler");
            empty.startGroundMobSimulation();
            check(taskField.get(empty) == null, "disposed map cannot restart simulation");
        } finally {
            server.TimerManager.getInstance().stop();
        }
        System.out.println("PASS server ground authority: " + cases + " real footholds, " + joins
                + " joins; config, entry, hits, death, movement rejection, respawn, legacy fallback");
    }
}
