import client.MapleCharacter;
import client.MapleClient;
import client.MapleJob;
import client.SkillFactory;
import client.MapleBuffStat;
import com.google.flatbuffers.FlatBufferBuilder;
import java.awt.Point;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.MapleInventoryType;
import config.YamlConfig;
import constants.skills.Assassin;
import constants.skills.Hermit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import net.server.channel.handlers.AbstractDealDamageHandler;
import net.server.channel.handlers.RangedAttackHandler;
import net.server.channel.handlers.FlatBufferAttackHandler;
import net.server.channel.handlers.TakeDamageHandler;
import protocol.generated.maple.combat.AttackRequest;
import protocol.generated.maple.combat.AttackResult;
import protocol.generated.maple.combat.AttackStatus;
import protocol.generated.maple.network.ClientMessage;
import protocol.generated.maple.network.Packet;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.maps.MapleMap;
import tools.data.input.*;

/**
 * Offline combat fixture. Run from HeavenMS with -Dwzpath=wz, passing BASIC,
 * LUCKY, DRAIN and AVENGER hex from ClawAttackPacketTest. No DB or sockets.
 */
public class ClawAttackTransportTest extends AbstractDealDamageHandler {
    private static boolean forceCritical;
    private static boolean mixedCritical;
    private static boolean mixedAccuracy;
    private static boolean allMiss;
    private static boolean immune;

    private static void isolateDatabase() throws SQLException {
        YamlConfig.config.server.DB_URL = "jdbc:claw-fixture:";
        DriverManager.registerDriver(new Driver() {
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:claw-fixture:"); }
            public Connection connect(String url, Properties props) throws SQLException {
                if (!acceptsURL(url)) return null;
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("close")) return null;
                    if (m.getName().equals("isClosed")) return false;
                    if (m.getName().equals("prepareStatement")
                            && (a[0].equals("SELECT cardid, mobid FROM monstercarddata")
                            || a[0].equals("SELECT * FROM drop_data_global WHERE chance > 0"))) {
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
                                    throw new SQLException("Unexpected fixture ResultSet call: " + o);
                                });
                            throw new SQLException("Unexpected fixture statement call: " + n);
                        });
                    }
                    throw new SQLException("Unexpected fixture DB call: " + m);
                });
            }
            public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
        });
    }

    private static class RecordingClient extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        RecordingClient() { super(null, null, null); }
        @Override public void announce(byte[] packet) { packets.add(packet); }
    }

    private static class TestMonster extends MapleMonster {
        int aggroCalls;
        TestMonster(MapleMap map) {
            this(map, 1000);
        }
        TestMonster(MapleMap map, int hp) {
            super(120100, stats(hp));
            setMap(map);
            setObjectId(123);
        }
        @Override public void aggroMonsterDamage(MapleCharacter chr, int damage) {
            // Controller/timer scheduling belongs to the separate controller fixture.
            aggroCalls++;
        }
        @Override public boolean isBuffed(client.status.MonsterStatus status) {
            return (immune && status == client.status.MonsterStatus.WEAPON_IMMUNITY) || super.isBuffed(status);
        }
        private static MapleMonsterStats stats(int hp) {
            MapleMonsterStats stats = new MapleMonsterStats();
            stats.setHp(hp);
            return stats;
        }
    }

    private static class RecordingMap extends MapleMap {
        final List<byte[]> broadcasts = new ArrayList<>();
        int damageCalls;
        RecordingMap() { super(100000000, 0, 1, 100000000, 1); }
        @Override public void broadcastMessage(byte[] packet) { broadcasts.add(packet); }
        @Override public void broadcastMessage(MapleCharacter source, byte[] packet, boolean repeat) { broadcasts.add(packet); }
        @Override public boolean damageMonster(MapleCharacter chr, MapleMonster mob, int damage) {
            damageCalls++;
            return super.damageMonster(chr, mob, damage);
        }
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

    private static MapleCharacter player(MapleMap map, int level) throws Exception {
        Constructor<MapleCharacter> ctor = MapleCharacter.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MapleCharacter chr = ctor.newInstance();
        field(MapleCharacter.class, "map").set(chr, map);
        field(MapleCharacter.class, "level").setInt(chr, level);
        field(MapleCharacter.class, "job").set(chr, MapleJob.ASSASSIN);
        field(MapleCharacter.class, "hp").setInt(chr, 1000);
        field(MapleCharacter.class, "mp").setInt(chr, 1000);
        field(MapleCharacter.class, "localluk").setInt(chr, 100);
        field(MapleCharacter.class, "localwatk").setInt(chr, 100);
        RecordingClient client = new RecordingClient();
        chr.setClient(client);
        client.setPlayer(chr);
        chr.getInventory(MapleInventoryType.EQUIPPED).addItemFromDB(new Equip(1472030, (short) -11));
        @SuppressWarnings("unchecked")
        Map<client.Skill, MapleCharacter.SkillEntry> skills =
                (Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr);
        skills.put(SkillFactory.getSkill(4001344), new MapleCharacter.SkillEntry((byte) 1, 20, -1));
        return chr;
    }

    private static SeekableLittleEndianAccessor payload(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        SeekableLittleEndianAccessor lea = new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(bytes));
        check(lea.readShort() == 0x2D, "native legacy ranged opcode");
        return lea;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }

    @Override public void handlePacket(SeekableLittleEndianAccessor lea, MapleClient c) {}

    private static void attack(String hex, int level, int stars, int expectedDamage, int expectedAmmo) throws Exception {
        attack(hex, level, stars, expectedDamage, expectedAmmo, 2070016);
    }

    private static void attack(String hex, int level, int stars, int expectedDamage,
                               int expectedAmmo, int ammoId) throws Exception {
        MapleMap map = new MapleMap(100000000, 0, 1, 100000000, 1);
        MapleCharacter chr = player(map, level);
        TestMonster monster = new TestMonster(map);
        @SuppressWarnings("unchecked")
        Map<Integer, server.maps.MapleMapObject> objects =
                (Map<Integer, server.maps.MapleMapObject>) field(MapleMap.class, "mapobjects").get(map);
        objects.put(123, monster); // Preserve the OID in the native packet.
        Item ammo = new Item(ammoId, (short) 1, (short) stars);
        chr.getInventory(MapleInventoryType.USE).addItemFromDB(ammo);
        new RangedAttackHandler().handlePacket(payload(hex), chr.getClient());
        check(monster.getHp() == 1000 - expectedDamage, "level " + level + " server HP, starting ammo " + stars);
        check(ammo.getQuantity() == expectedAmmo, "level " + level + " remaining ammo " + expectedAmmo);
    }

    @SuppressWarnings("unchecked")
    private static void buff(MapleCharacter chr, MapleBuffStat stat, int sourceId) throws Exception {
        Class<?> holder = Class.forName("client.MapleCharacter$MapleBuffStatValueHolder");
        Constructor<?> ctor = holder.getDeclaredConstructor(server.MapleStatEffect.class, long.class, int.class);
        ctor.setAccessible(true);
        ((Map<MapleBuffStat, Object>) field(MapleCharacter.class, "effects").get(chr)).put(stat,
                ctor.newInstance(SkillFactory.getSkill(sourceId).getEffect(1), 0L, 1));
    }

    private static AttackRequest intent(int skillId) {
        FlatBufferBuilder b = new FlatBufferBuilder(128);
        int targets = AttackRequest.createTargetOidsVector(b, new int[]{123});
        // Skill level is deliberately forged: only the server's learned level counts.
        int attack = AttackRequest.createAttackRequest(b, 1, skillId, 255, 23, false, 6, 0, (short) 0, (short) 0, targets);
        Packet.startPacket(b);
        Packet.addClientMessageType(b, ClientMessage.protocol_generated_maple_combat_AttackRequest);
        Packet.addClientMessage(b, attack);
        Packet.finishPacketBuffer(b, Packet.endPacket(b));
        ByteBuffer bytes = ByteBuffer.wrap(b.sizedByteArray());
        check(Packet.PacketBufferHasIdentifier(bytes), "MSFB request identifier, no opcode");
        AttackRequest request = new AttackRequest();
        Packet.getRootAsPacket(bytes).clientMessage(request);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static void flatAttack(int skillId, MapleJob job, int weaponId, int ammoId,
                                   int quantity, boolean shadow, MapleBuffStat free,
                                   int mp, int status, int consumed) throws Exception {
        flatAttack(skillId, job, weaponId, ammoId, quantity, shadow, free, mp, status, consumed, false);
    }

    @SuppressWarnings("unchecked")
    private static void flatAttack(int skillId, MapleJob job, int weaponId, int ammoId,
                                   int quantity, boolean shadow, MapleBuffStat free,
                                   int mp, int status, int consumed, boolean cooling) throws Exception {
        RecordingMap map = new RecordingMap();
        MapleCharacter chr = player(map, 120);
        field(MapleCharacter.class, "job").set(chr, job);
        field(MapleCharacter.class, "mp").setInt(chr, mp);
        field(MapleCharacter.class, "localmaxmp").setInt(chr, 1000);
        field(MapleCharacter.class, "localmaxhp").setInt(chr, 1000);
        field(MapleCharacter.class, "localdex").setInt(chr, 100);
        field(MapleCharacter.class, "localstr").setInt(chr, 20);
        Item weapon = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((short) -11);
        field(Item.class, "id").setInt(weapon, weaponId);
        Map<client.Skill, MapleCharacter.SkillEntry> skills =
                (Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr);
        skills.put(SkillFactory.getSkill(skillId), new MapleCharacter.SkillEntry((byte) 1, 30, -1));
        if (forceCritical || mixedCritical)
            skills.put(SkillFactory.getSkill(4100001), new MapleCharacter.SkillEntry((byte) 1, 20, -1));
        if (shadow) buff(chr, MapleBuffStat.SHADOWPARTNER, 4111002);
        if (free != null) buff(chr, free, free == MapleBuffStat.SOULARROW ? 3101004 : 4121006);
        Item ammo = new Item(ammoId, (short) 1, (short) quantity);
        chr.getInventory(MapleInventoryType.USE).addItemFromDB(ammo);
        TestMonster monster = new TestMonster(map, 1000000);
        if (mixedAccuracy || allMiss) {
            monster.getStats().setLevel(120);
            monster.getStats().setEvasion(allMiss ? 1000000 : 33);
        }
        monster.setPosition(new Point(100, 0));
        ((Map<Integer, server.maps.MapleMapObject>) field(MapleMap.class, "mapobjects").get(map)).put(123, monster);
        FlatBufferAttackHandler.forget(chr.getId());
        if (cooling) chr.addCooldown(skillId, System.currentTimeMillis(), 60000);
        FlatBufferAttackHandler handler = new FlatBufferAttackHandler();
        AttackRequest request = intent(skillId);
        if (mixedCritical || mixedAccuracy || allMiss)
            ((Random) field(tools.Randomizer.class, "rand").get(null)).setSeed(4096);
        handler.handleFlatBufferAttack(request, chr.getClient());
        List<byte[]> packets = status == AttackStatus.Ok ? map.broadcasts : ((RecordingClient) chr.getClient()).packets;
        AttackResult result = null;
        for (byte[] bytes : packets) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            if (bytes.length >= 8 && Packet.PacketBufferHasIdentifier(bb)) {
                result = new AttackResult();
                Packet.getRootAsPacket(bb).serverMessage(result);
                break;
            }
        }
        check(result != null && result.status() == status, "FlatBuffer status " + skillId + "/" + status);
        check(ammo.getQuantity() == quantity - consumed, "FlatBuffer ammo " + skillId);
        if (status == AttackStatus.Ok) {
            int expected = skillId == 0 ? 1 : (skillId == 3111006 || skillId == 3211006) ? 4 :
                    (skillId == 4121007 || skillId == 14111005) ? 3 : 2;
            expected *= shadow ? 2 : 1;
            check(result.skillLevel() == (skillId == 0 ? 0 : 1) && result.hitCount() == expected, "authoritative skill level and N hits");
            check(result.targetsLength() == 1 && result.targets(0).damagesLength() == expected, "per-hit target lines");
            int damage = 0;
            int misses = 0, hits = 0;
            for (int i = 0; i < expected; ++i) {
                damage += result.targets(0).damages(i);
                if (result.targets(0).damages(i) == 0) {
                    misses++;
                    check(!result.targets(0).crits(i), "zero-damage line is noncritical");
                } else {
                    hits++;
                    if (forceCritical && !immune) check(result.targets(0).crits(i), "forced authoritative critical hit");
                    if (immune) check(!result.targets(0).crits(i), "immune result is noncritical");
                }
                if (shadow && i >= expected / 2) {
                    server.MapleStatEffect partner = chr.getBuffEffect(MapleBuffStat.SHADOWPARTNER);
                    double scale = (skillId == 0 ? partner.getX() : partner.getY()) / 100.0;
                    check(result.targets(0).damages(i) == (int) (result.targets(0).damages(i - expected / 2) * scale),
                            "secondary line uses server WZ x/y scaling");
                    check(result.targets(0).crits(i) == result.targets(0).crits(i - expected / 2),
                            "secondary line inherits corresponding critical flag");
                }
            }
            if (mixedAccuracy) check(misses > 0 && hits > 0, "accuracy produces both misses and hits in production handler");
            if (allMiss) check(misses == expected && hits == 0 && monster.aggroCalls == 0
                    && map.damageCalls == 0 && monster.getHp() == 1000000,
                    "all-miss target causes no aggro, damage call or HP loss");
            if (mixedCritical)
                check(result.targets(0).crits(0) && !result.targets(0).crits(1) && result.targets(0).crits(2)
                        && result.targets(0).crits(3) && !result.targets(0).crits(4) && result.targets(0).crits(5),
                        "Triple Throw mixed criticals match their Shadow Partner copies");
            check(monster.getHp() == 1000000 - damage, "rolled result equals applyAttack HP mutation");
            int afterMp = chr.getMp();
            int afterAmmo = ammo.getQuantity();
            int broadcasts = map.broadcasts.size();
            handler.handleFlatBufferAttack(request, chr.getClient());
            check(chr.getMp() == afterMp && ammo.getQuantity() == afterAmmo
                    && monster.getHp() == 1000000 - damage && map.broadcasts.size() == broadcasts,
                    "duplicate request never consumes or applies twice");
            check(afterMp == mp - (skillId == 0 ? 0 : SkillFactory.getSkill(skillId).getEffect(1).getMpCon()), "MP consumed once");
        } else {
            check(monster.getHp() == 1000000 && chr.getMp() == mp, "rejection consumes no HP/MP");
        }
    }

    @SuppressWarnings("unchecked")
    private static int nimbleAttack(MapleJob job, int level, long seed) throws Exception {
        RecordingMap map = new RecordingMap();
        MapleCharacter chr = player(map, 120);
        field(MapleCharacter.class, "job").set(chr, job);
        field(MapleCharacter.class, "localdex").setInt(chr, 100);
        int id = job == MapleJob.ASSASSIN ? 4000000 : 14000000;
        if (level > 0) {
            ((Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr))
                    .put(SkillFactory.getSkill(id), new MapleCharacter.SkillEntry((byte) level, 20, -1));
        }
        Item ammo = new Item(2070006, (short) 1, (short) 1);
        chr.getInventory(MapleInventoryType.USE).addItemFromDB(ammo);
        TestMonster mob = new TestMonster(map, 1000000);
        mob.getStats().setLevel(120);
        mob.getStats().setEvasion(30);
        mob.setPosition(new Point(100, 0));
        ((Map<Integer, server.maps.MapleMapObject>) field(MapleMap.class, "mapobjects").get(map)).put(123, mob);
        FlatBufferAttackHandler.forget(chr.getId());
        AttackRequest request = intent(0);
        ((Random) field(tools.Randomizer.class, "rand").get(null)).setSeed(seed);
        new FlatBufferAttackHandler().handleFlatBufferAttack(request, chr.getClient());
        AttackResult result = new AttackResult();
        Packet.getRootAsPacket(ByteBuffer.wrap(map.broadcasts.get(0))).serverMessage(result);
        int damage = result.targets(0).damages(0);
        check(mob.getHp() == 1000000 - damage, "Nimble integration result matches HP");
        return damage;
    }

    private static SeekableLittleEndianAccessor touchPacket(int damage) {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).put((byte) -1).put((byte) 0).putInt(damage).putInt(120100).putInt(123).put((byte) 0);
        return new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(b.array()));
    }

    @SuppressWarnings("unchecked")
    private static void touchMissTests() throws Exception {
        RecordingMap map = new RecordingMap();
        MapleCharacter chr = player(map, 120);
        client.autoban.AutobanManager autoban = new client.autoban.AutobanManager(chr);
        field(MapleCharacter.class, "autoban").set(chr, autoban);
        TestMonster monster = new TestMonster(map);
        ((Map<Integer, server.maps.MapleMapObject>) field(MapleMap.class, "mapobjects").get(map)).put(123, monster);
        TakeDamageHandler handler = new TakeDamageHandler();
        field(client.autoban.AutobanManager.class, "samemisscount").setInt(autoban, 4);
        field(client.autoban.AutobanManager.class, "lastmisses").setInt(autoban, 8);
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 8; i++) handler.handlePacket(touchPacket(0), chr.getClient());
            handler.handlePacket(touchPacket(1), chr.getClient());
        }
        check(field(client.autoban.AutobanManager.class, "misses").getInt(autoban) == 0
                && field(client.autoban.AutobanManager.class, "samemisscount").getInt(autoban) == 4,
                "repeated zero touch dodges do not alter the miss godmode heuristic");
        handler.handlePacket(touchPacket(-1), chr.getClient());
        check(field(client.autoban.AutobanManager.class, "misses").getInt(autoban) == 1,
                "fake-skill -1 damage retains legacy miss tracking");
        byte[] packet = map.broadcasts.get(map.broadcasts.size() - 1);
        check(ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(packet.length - 4) == 4120002,
                "fake-skill -1 damage retains legacy fake-skill broadcast");
        handler.handlePacket(touchPacket(0), chr.getClient());
        check(field(client.autoban.AutobanManager.class, "misses").getInt(autoban) == 1,
                "touch dodge preserves unrelated fake-skill streaks");
    }

    @SuppressWarnings("unchecked")
    private static void accuracyTests() throws Exception {
        Method accuracy = FlatBufferAttackHandler.class.getDeclaredMethod("calculateAccuracy",
                MapleCharacter.class, client.inventory.MapleWeaponType.class);
        accuracy.setAccessible(true);
        Method chance = FlatBufferAttackHandler.class.getDeclaredMethod("hitChance",
                double.class, int.class, int.class, int.class);
        chance.setAccessible(true);
        check((double) chance.invoke(null, 0.0, 10, 10, 100) == 0.0, "minimum hit chance");
        check((double) chance.invoke(null, 999.0, 10, 10, 0) == 1.0, "maximum hit chance");
        double expected = 150.0 / ((1.84 + 0.07 * 10) * 40) - 1;
        check(Math.abs((double) chance.invoke(null, 150.0, 10, 20, 40) - expected) < 1e-12,
                "level penalty and WZ evasion denominator");
        check((double) chance.invoke(null, 100.0, 20, 10, 40)
                == (double) chance.invoke(null, 100.0, 20, 20, 40), "lower monster level has no bonus");
        check((double) chance.invoke(null, 74.0, 20, 20, 20) == 1.0
                && (double) chance.invoke(null, 73.0, 20, 20, 20) < 1.0,
                "physical guaranteed-hit threshold");
        check(server.life.MapleLifeFactory.getMonster(210100).getStats().getEvasion() == 1
                && server.life.MapleLifeFactory.getMonster(1110100).getStats().getEvasion() == 5,
                "real monster WZ eva values loaded");
        check(server.life.MapleLifeFactory.getMonster(1110100).getStats().copy().getEvasion() == 5,
                "monster stat copies retain eva");

        for (int id : new int[]{4000000, 14000000}) {
            int max = id == 4000000 ? 20 : 10;
            for (int level = 1; level <= max; level++) {
                check(SkillFactory.getSkill(id).getEffect(level).getX() == (id == 4000000 ? level : 2 * level),
                        "WZ Nimble Body " + id + " level " + level);
            }
        }
        double without = 90.0 / 55.2 - 1, with = 110.0 / 55.2 - 1;
        long seed = 0;
        while (new Random(seed).nextDouble() < without || new Random(seed).nextDouble() >= with) seed++;
        for (MapleJob job : new MapleJob[]{MapleJob.ASSASSIN, MapleJob.NIGHTWALKER1}) {
            int max = job == MapleJob.ASSASSIN ? 20 : 10;
            check(nimbleAttack(job, 0, seed) == 0 && nimbleAttack(job, max, seed) > 0,
                    "same seeded roll crosses actual WZ Nimble chance threshold " + job);
        }
        for (MapleJob job : new MapleJob[]{MapleJob.ASSASSIN, MapleJob.NIGHTWALKER2}) {
            MapleCharacter chr = player(new RecordingMap(), 30);
            field(MapleCharacter.class, "job").set(chr, job);
            field(MapleCharacter.class, "localdex").setInt(chr, 101);
            field(MapleCharacter.class, "localluk").setInt(chr, 21);
            Equip weapon = (Equip) chr.getInventory(MapleInventoryType.EQUIPPED).getItem((short) -11);
            weapon.setAcc((short) 7);
            buff(chr, MapleBuffStat.ACC, 3101004);
            Map<client.Skill, MapleCharacter.SkillEntry> skills =
                    (Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr);
            int nimble = job == MapleJob.ASSASSIN ? 4000000 : 14000000;
            int mastery = job == MapleJob.ASSASSIN ? 4100000 : 14100000;
            skills.put(SkillFactory.getSkill(nimble), new MapleCharacter.SkillEntry((byte) 3, 20, -1));
            skills.put(SkillFactory.getSkill(mastery), new MapleCharacter.SkillEntry((byte) 4, 20, -1));
            int blessing = job == MapleJob.ASSASSIN ? 12 : 10000012;
            skills.put(SkillFactory.getSkill(blessing), new MapleCharacter.SkillEntry((byte) 5, 20, -1));
            double actual = (double) accuracy.invoke(null, chr, client.inventory.MapleWeaponType.CLAW);
            check(actual == 66 + 7 + 1 + (job == MapleJob.ASSASSIN ? 3 : 6) + 4
                            + SkillFactory.getSkill(blessing).getEffect(5).getZ(),
                    "total stats, equipment, ACC buff, WZ Nimble, mastery and blessing " + job);
        }
    }

    private static void flatBuffers() throws Exception {
        accuracyTests();
        touchMissTests();
        flatAttack(0, MapleJob.HERMIT, 1472030, 2070006, 1, false, null, 1000, AttackStatus.Ok, 1);
        flatAttack(0, MapleJob.HERMIT, 1472030, 2070006, 2, true, null, 1000, AttackStatus.Ok, 2);
        Method bounds = FlatBufferAttackHandler.class.getDeclaredMethod("calculateSkillDamageBounds",
                MapleCharacter.class, client.inventory.MapleWeaponType.class, int.class, server.MapleStatEffect.class);
        bounds.setAccessible(true);
        MapleCharacter chr = player(new RecordingMap(), 120);
        for (int id : new int[]{4001344, 14001004, 4121007, 14111005}) {
            server.MapleStatEffect effect = SkillFactory.getSkill(id).getEffect(1);
            double[] range = (double[]) bounds.invoke(null, chr, client.inventory.MapleWeaponType.CLAW, id, effect);
            check(Math.abs(range[0] - 250 * effect.getDamage() / 100.0) < 1e-8
                    && Math.abs(range[1] - 500 * effect.getDamage() / 100.0) < 1e-8,
                    "LUK-only 2.5/5 bounds " + id);
        }
        field(MapleCharacter.class, "localwatk").setInt(chr, 37);
        field(MapleCharacter.class, "localstr").setInt(chr, 999);
        field(MapleCharacter.class, "localdex").setInt(chr, 999);
        @SuppressWarnings("unchecked")
        Map<client.Skill, MapleCharacter.SkillEntry> learned =
                (Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr);
        learned.put(SkillFactory.getSkill(4100000), new MapleCharacter.SkillEntry((byte) 20, 20, -1));
        server.MapleStatEffect luckyEffect = SkillFactory.getSkill(4001344).getEffect(1);
        double[] fractional = (double[]) bounds.invoke(null, chr, client.inventory.MapleWeaponType.CLAW, 4001344, luckyEffect);
        check(Math.abs(fractional[0] - 2.5 * 100 * 0.37 * 0.58) < 1e-8
                && Math.abs(fractional[1] - 5.0 * 100 * 0.37 * 0.58) < 1e-8,
                "fractional WATK, mastery and secondary stats do not corrupt Lucky Seven bounds");
        field(MapleCharacter.class, "localwatk").setInt(chr, 100);
        field(MapleCharacter.class, "localstr").setInt(chr, 20);
        field(MapleCharacter.class, "localdex").setInt(chr, 100);
        learned.put(SkillFactory.getSkill(3100000), new MapleCharacter.SkillEntry((byte) 20, 20, -1));
        double[] bow = (double[]) bounds.invoke(null, chr, client.inventory.MapleWeaponType.BOW, 3001005,
                SkillFactory.getSkill(3001005).getEffect(1));
        double primary = client.inventory.MapleWeaponType.BOW.getMaxDamageMultiplier() * 100;
        check(Math.abs(bow[0] - (primary * 0.9 * 0.6 + 20) * 0.92) < 1e-8
                && Math.abs(bow[1] - (primary + 20) * 0.92) < 1e-8, "WZ mastery and DEX/STR bow bounds");
        // Exercise real generated request -> production handler -> result -> applyAttack.
        for (int id : new int[]{4001344, 14001004, 4121007, 14111005}) {
            MapleJob job = id == 4001344 ? MapleJob.ASSASSIN : id == 14001004 ? MapleJob.NIGHTWALKER1 :
                    id == 4121007 ? MapleJob.NIGHTLORD : MapleJob.NIGHTWALKER3;
            int count = id == 4001344 || id == 14001004 ? 2 : 3;
            flatAttack(id, job, 1472030, 2070006, count, false, null, 1000, AttackStatus.Ok, count);
            flatAttack(id, job, 1472030, 2070006, count * 2, true, null, 1000, AttackStatus.Ok, count * 2);
            flatAttack(id, job, 1472030, 2070006, count - 1, false, null, 1000, AttackStatus.NoAmmo, 0);
            flatAttack(id, job, 1472030, 2070006, 0, false, MapleBuffStat.SHADOW_CLAW, 1000, AttackStatus.Ok, 0);
            flatAttack(id, job, 1472030, 2070006, count, false, null, 0, AttackStatus.Rejected, 0);
        }
        flatAttack(3001005, MapleJob.BOWMAN, 1452000, 2060000, 2, false, null, 1000, AttackStatus.Ok, 2);
        flatAttack(13001003, MapleJob.WINDARCHER1, 1452000, 2060000, 2, false, null, 1000, AttackStatus.Ok, 2);
        flatAttack(3111006, MapleJob.RANGER, 1452000, 2060000, 4, false, null, 1000, AttackStatus.Ok, 4);
        flatAttack(3211006, MapleJob.SNIPER, 1462000, 2061000, 4, false, null, 1000, AttackStatus.Ok, 4);
        flatAttack(3001005, MapleJob.BOWMAN, 1452000, 2060000, 0, false, MapleBuffStat.SOULARROW,
                1000, AttackStatus.Ok, 0);
        flatAttack(4001344, MapleJob.ASSASSIN, 1452000, 2060000, 2, false, null,
                1000, AttackStatus.InvalidWeapon, 0);
        flatAttack(4001344, MapleJob.BOWMAN, 1472030, 2070006, 2, false, null,
                1000, AttackStatus.Rejected, 0);
        flatAttack(4001344, MapleJob.ASSASSIN, 1472030, 2070006, 2, false, null,
                1000, AttackStatus.Rejected, 0, true);
        flatAttack(5201001, MapleJob.GUNSLINGER, 1492000, 2330000, 2, false, null,
                1000, AttackStatus.Unsupported, 0);
        Field consume = field(server.MapleStatEffect.class, "bulletConsume");
        short original = consume.getShort(luckyEffect);
        try {
            consume.setShort(luckyEffect, (short) 1);
            flatAttack(4001344, MapleJob.ASSASSIN, 1472030, 2070006, 1, false, null,
                    1000, AttackStatus.Ok, 1);
        } finally {
            consume.setShort(luckyEffect, original);
        }
        server.MapleStatEffect critical = SkillFactory.getSkill(4100001).getEffect(1);
        Field probability = field(server.MapleStatEffect.class, "prop");
        double originalProbability = probability.getDouble(critical);
        try {
            probability.setDouble(critical, 1.0);
            forceCritical = true;
            flatAttack(4001344, MapleJob.ASSASSIN, 1472030, 2070006, 2, false, null,
                    1000, AttackStatus.Ok, 2);
            flatAttack(4001344, MapleJob.ASSASSIN, 1472030, 2070006, 4, true, null,
                    1000, AttackStatus.Ok, 4);
            forceCritical = false;
            probability.setDouble(critical, 0.5);
            mixedCritical = true;
            flatAttack(4121007, MapleJob.NIGHTLORD, 1472030, 2070006, 6, true, null,
                    1000, AttackStatus.Ok, 6);
            mixedCritical = false;
            probability.setDouble(critical, 1.0);
            forceCritical = true;
            mixedAccuracy = true;
            flatAttack(4121007, MapleJob.NIGHTLORD, 1472030, 2070006, 6, true, null,
                    1000, AttackStatus.Ok, 6);
            mixedAccuracy = false;
            allMiss = true;
            immune = true;
            flatAttack(4121007, MapleJob.NIGHTLORD, 1472030, 2070006, 6, true, null,
                    1000, AttackStatus.Ok, 6);
            allMiss = false;
            mixedAccuracy = true;
            flatAttack(4121007, MapleJob.NIGHTLORD, 1472030, 2070006, 6, true, null,
                    1000, AttackStatus.Ok, 6);
        } finally {
            forceCritical = false;
            mixedCritical = false;
            mixedAccuracy = false;
            allMiss = false;
            immune = false;
            probability.setDouble(critical, originalProbability);
        }
    }

        @SuppressWarnings("unchecked")
        private static void legacyShadow() throws Exception {
            for (int skillId : new int[]{0, 1001004, 2001005, 5201001}) {
                for (boolean shadow : new boolean[]{false, true}) {
                    RecordingMap map = new RecordingMap();
                    MapleCharacter chr = player(map, 120);
                    field(MapleCharacter.class, "name").set(chr, "ShadowFixture");
                    field(MapleCharacter.class, "localstr").setInt(chr, 100);
                    field(MapleCharacter.class, "localdex").setInt(chr, 100);
                    field(MapleCharacter.class, "localmagic").setInt(chr, 1000);
                    if (skillId != 0)
                        ((Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr))
                                .put(SkillFactory.getSkill(skillId), new MapleCharacter.SkillEntry((byte) 1, 30, -1));
                    if (shadow) buff(chr, MapleBuffStat.SHADOWPARTNER, 4111002);
                    TestMonster monster = new TestMonster(map, 1000000);
                    ((Map<Integer, server.maps.MapleMapObject>) field(MapleMap.class, "mapobjects").get(map)).put(123, monster);
                    boolean ranged = skillId == 5201001;
                    boolean magic = skillId == 2001005;
                    int base = magic || ranged ? 2 : 1;
                    int hits = base * (shadow ? 2 : 1);
                    int targetOffset = ranged ? 28 : 23;
                    ByteBuffer b = ByteBuffer.allocate(targetOffset + 18 + hits * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
                    b.put(1, (byte) (0x10 | hits));
                    b.putInt(2, skillId);
                    b.putInt(targetOffset, 123);
                    for (int hit = 0; hit < hits; ++hit)
                        b.putInt(targetOffset + 18 + hit * 4, hit < base ? 100 : 999999);
                    ClawAttackTransportTest handler = new ClawAttackTransportTest();
                    AttackInfo attack = handler.parseDamage(new GenericSeekableLittleEndianAccessor(
                            new ByteArrayByteStream(b.array())), chr, ranged, magic);
                    List<Integer> lines = attack.allDamage.get(123);
                    check(lines.size() == hits, "legacy melee/skill/magic/ranged line count " + skillId + "/" + shadow);
                    int sum = 0;
                    for (int hit = 0; hit < hits; ++hit) {
                        int encoded = lines.get(hit);
                        int raw = encoded < 0 ? encoded + Integer.MAX_VALUE : encoded;
                        if (shadow && hit >= base) {
                            int original = lines.get(hit - base);
                            int primary = original < 0 ? original + Integer.MAX_VALUE : original;
                            check(raw == (int) (primary * shadowPartnerScale(chr, skillId)),
                                    "legacy forged secondary replaced using WZ scale");
                        }
                        sum += raw;
                    }
                    handler.applyAttack(attack, chr, hits);
                    check(monster.getHp() == 1000000 - sum, "legacy replicated damage equals HP loss");
                }
            }
        }

    @SuppressWarnings("unchecked")
    private static void legacyClawSkill(String hex, int id, int startingHp, int targets,
                                        int stars, int consumed, int expectedHeal) throws Exception {
        RecordingMap map = new RecordingMap() {
            @Override public void killMonster(MapleMonster monster, MapleCharacter chr, boolean withDrops) {
                // Keep actual damage, but skip drop/world setup for lethal Drain.
            }
        };
        MapleCharacter chr = player(map, 120);
        field(MapleCharacter.class, "job").set(chr, id == Assassin.DRAIN ? MapleJob.ASSASSIN : MapleJob.HERMIT);
        field(MapleCharacter.class, "hp").setInt(chr, 300);
        field(MapleCharacter.class, "localmaxhp").setInt(chr, 1000);
        field(MapleCharacter.class, "localmaxmp").setInt(chr, 1000);
        field(MapleCharacter.class, "name").set(chr, "ClawSkillFixture");
        ((Map<client.Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr))
                .put(SkillFactory.getSkill(id), new MapleCharacter.SkillEntry((byte) 1, 30, -1));
        Map<Integer, server.maps.MapleMapObject> objects =
                (Map<Integer, server.maps.MapleMapObject>) field(MapleMap.class, "mapobjects").get(map);
        for (int i = 0; i < targets; ++i) {
            TestMonster mob = new TestMonster(map, startingHp);
            mob.setObjectId(123 + i);
            objects.put(mob.getObjectId(), mob);
        }
        Item ammo = new Item(2070006, (short) 1, (short) stars);
        chr.getInventory(MapleInventoryType.USE).addItemFromDB(ammo);
        int mp = chr.getMp();
        new RangedAttackHandler().handlePacket(payload(hex), chr.getClient());
        check(ammo.getQuantity() == stars - consumed, "legacy claw ammo " + id);
        check(chr.getMp() == mp - (consumed == 0 ? 0 : SkillFactory.getSkill(id).getEffect(1).getMpCon()),
                "legacy claw MP " + id);
        check(chr.getHp() == 300 + expectedHeal, "legacy claw healing " + id + " at target HP " + startingHp);
        for (int i = 0; i < targets; ++i)
            check(map.getMonsterByOid(123 + i).getHp() == Math.max(0, startingHp - (consumed == 0 ? 0 : 100)),
                    "legacy claw damage to target " + (123 + i));
    }

    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_DEBUG = false;
        isolateDatabase();
        SkillFactory.loadAllSkills();
        flatBuffers();
        legacyShadow();
        if (args.length == 0) return; // FB-only mode; existing two-hex legacy mode remains.
        check(args.length == 2 || args.length == 4, "native BASIC and LUCKY, optionally DRAIN and AVENGER");
        if (args.length == 4) {
            legacyClawSkill(args[2], Assassin.DRAIN, 1000, 1, 1, 1, 16);
            legacyClawSkill(args[2], Assassin.DRAIN, 50, 1, 1, 1, 8);
            legacyClawSkill(args[3], Hermit.AVENGER, 1000, 4, 3, 3, 0);
            legacyClawSkill(args[3], Hermit.AVENGER, 1000, 4, 2, 0, 0);
        }
        MapleMap map = new MapleMap(100000000, 0, 1, 100000000, 1);
        MapleCharacter chr = player(map, 50);
        SeekableLittleEndianAccessor lea = payload(args[1]);
        AttackInfo lucky = new ClawAttackTransportTest().parseDamage(lea, chr, true, false);
        check(lucky.skill == 4001344 && lucky.numDamage == 2 && lucky.numAttacked == 1, "Lucky Seven attack header");
        check(lucky.allDamage.get(123).equals(Arrays.asList(10, 20)), "Lucky Seven native two-hit damage parsing");
        check(lea.available() == 0, "Lucky Seven consumes complete native payload");
        attack(args[0], 35, 100, 0, 100);
        attack(args[0], 50, 100, 10, 99);
        attack(args[0], 50, 1, 10, 0);
        attack(args[0], 50, 0, 0, 0);
        attack(args[1], 35, 100, 0, 100);
        attack(args[1], 50, 2, 30, 0);
        attack(args[1], 50, 1, 0, 1);
        // Regular Ilbis remain usable at level 35; no ammo-level rule was weakened.
        attack(args[0], 35, 1, 10, 0, 2070006);
        attack(args[1], 35, 2, 30, 0, 2070006);
        // Regression: the availability gate must use consumption, not hit count.
        // Process-local synthetic effect; restore the actual WZ value afterwards.
        server.MapleStatEffect effect = SkillFactory.getSkill(4001344).getEffect(1);
        Field consume = field(server.MapleStatEffect.class, "bulletConsume");
        short original = consume.getShort(effect);
        try {
            consume.setShort(effect, (short) 1);
            attack(args[1], 50, 1, 30, 0);
        } finally {
            consume.setShort(effect, original);
        }
    }
}
