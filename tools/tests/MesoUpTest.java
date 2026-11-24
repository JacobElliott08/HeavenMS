import client.MapleBuffStat;
import client.MapleCharacter;
import client.MapleClient;
import client.MapleJob;
import client.Skill;
import client.SkillFactory;
import client.autoban.AutobanManager;
import config.YamlConfig;
import constants.skills.Hermit;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.server.channel.handlers.SpecialMoveHandler;
import net.server.world.MapleParty;
import net.server.world.MaplePartyCharacter;
import protocol.generated.maple.character.BuffApply;
import protocol.generated.maple.character.BuffStat;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.MapleStatEffect;
import server.life.MapleMonster;
import server.life.MapleMonsterStats;
import server.life.MonsterDropEntry;
import server.maps.MapleMap;
import server.maps.MapleMapItem;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;

/** Offline fixture: existing WZ, SPECIAL_MOVE, party buffs, and monster drops. */
public class MesoUpTest {
    static class Client extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        Client() { super(null, null, null); }
        @Override public void announce(byte[] bytes) { packets.add(bytes); }
    }
    static class MapFixture extends MapleMap {
        MapFixture() { super(100000000, 0, 1, 100000000, 1); }
        @Override public void broadcastMessage(MapleCharacter chr, byte[] bytes, boolean repeat) {}
        @Override public Point calcDropPos(Point point, Point fallback) { return point; }
    }
    static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static MapleCharacter player(MapFixture map, int id, int mp, int x) throws Exception {
        Constructor<MapleCharacter> ctor = MapleCharacter.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MapleCharacter chr = ctor.newInstance();
        field(MapleCharacter.class, "id").setInt(chr, id);
        field(MapleCharacter.class, "name").set(chr, "fixture" + id);
        field(MapleCharacter.class, "job").set(chr, MapleJob.HERMIT);
        field(MapleCharacter.class, "level").setInt(chr, 80);
        field(MapleCharacter.class, "map").set(chr, map);
        field(MapleCharacter.class, "hp").setInt(chr, 1000);
        field(MapleCharacter.class, "mp").setInt(chr, mp);
        field(MapleCharacter.class, "maxhp").setInt(chr, 1000);
        field(MapleCharacter.class, "maxmp").setInt(chr, 1000);
        field(MapleCharacter.class, "localmaxhp").setInt(chr, 1000);
        field(MapleCharacter.class, "localmaxmp").setInt(chr, 1000);
        field(MapleCharacter.class, "mesoRate").setInt(chr, 2);
        field(MapleCharacter.class, "autoban").set(chr, new AutobanManager(chr));
        Client c = new Client(); chr.setClient(c); c.setPlayer(chr);
        chr.setPosition(new Point(x, 0));
        map.addMapObject(chr);
        return chr;
    }
    static void learn(MapleCharacter chr, Skill skill, int level) throws Exception {
        @SuppressWarnings("unchecked")
        Map<Skill, MapleCharacter.SkillEntry> skills =
                (Map<Skill, MapleCharacter.SkillEntry>) field(MapleCharacter.class, "skills").get(chr);
        skills.put(skill, new MapleCharacter.SkillEntry((byte) level, 20, -1));
    }
    static void cast(MapleCharacter chr, int level) {
        ByteBuffer b = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).putInt(Hermit.MESO_UP).put((byte) level);
        new SpecialMoveHandler().handlePacket(
                new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(b.array())), chr.getClient());
    }
    static BuffApply findBuff(MapleCharacter chr) {
        for (byte[] bytes : ((Client) chr.getClient()).packets) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            if (bytes.length >= 8 && Packet.PacketBufferHasIdentifier(b)) {
                Packet packet = Packet.getRootAsPacket(b);
                if (packet.serverMessageType() == ServerMessage.protocol_generated_maple_character_BuffApply) {
                    BuffApply buff = new BuffApply(); packet.serverMessage(buff); return buff;
                }
            }
        }
        throw new AssertionError("missing MSFB BuffApply");
    }
    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        YamlConfig.config.server.USE_BUFF_EVERLASTING = false;
        // Reuse the neighboring combat fixture's in-memory DB isolation.
        Method isolation = ClawAttackTransportTest.class.getDeclaredMethod("isolateDatabase");
        isolation.setAccessible(true); isolation.invoke(null);
        SkillFactory.loadAllSkills();
        Skill skill = SkillFactory.getSkill(Hermit.MESO_UP);
        Method partyBuff = MapleStatEffect.class.getDeclaredMethod("isPartyBuff");
        partyBuff.setAccessible(true);
        for (int level = 1; level <= 20; level++) {
            MapleStatEffect effect = skill.getEffect(level);
            int x = level <= 10 ? 100 + 3 * level : 130 + 2 * (level - 10);
            check(effect.getX() == x && effect.getDuration() == (20 + 5 * level) * 1000,
                    "WZ x/time level " + level);
            check(effect.getMpCon() == 45 + 5 * ((level - 1) / 5), "WZ MP level " + level);
            check((boolean) partyBuff.invoke(effect), "party bounds level " + level);
        }
        MapFixture map = new MapFixture();
        MapleCharacter caster = player(map, 1, 500, 0);
        MapleCharacter member = player(map, 2, 400, 100);
        MapleCharacter distant = player(map, 3, 400, 500);
        MapleCharacter outsider = player(map, 4, 400, 50);
        MapleParty party = new MapleParty(1, new MaplePartyCharacter(caster));
        caster.setParty(party); member.setParty(party); distant.setParty(party);
        learn(caster, skill, 20);
        cast(caster, 19);
        check(caster.getBuffedValue(MapleBuffStat.MESOUP) == null && caster.getMp() == 500,
                "mismatched learned level rejected");
        cast(caster, 20);
        check(caster.getMp() == 440 && member.getMp() == 400, "MP charged only to caster");
        check(Integer.valueOf(150).equals(caster.getBuffedValue(MapleBuffStat.MESOUP))
                && Integer.valueOf(150).equals(member.getBuffedValue(MapleBuffStat.MESOUP)),
                "caster and nearby party receive x");
        check(distant.getBuffedValue(MapleBuffStat.MESOUP) == null
                && outsider.getBuffedValue(MapleBuffStat.MESOUP) == null, "range and party filtering");
        BuffApply wire = findBuff(member);
        check(wire.sourceId() == Hermit.MESO_UP && wire.durationMs() == 120000
                && wire.statupsLength() == 1 && wire.statups(0).stat() == BuffStat.MesoUp
                && wire.statups(0).value() == 150, "MSFB source, duration and typed value");
        MapleMonsterStats stats = new MapleMonsterStats(); stats.setHp(100);
        MapleMonster monster = new MapleMonster(100100, stats);
        monster.setMap(map); monster.setPosition(new Point(0, 0));
        // A one-wide random interval makes the production drop amount deterministic.
        List<MonsterDropEntry> drops = List.of(new MonsterDropEntry(0, 1000000, 101, 102, (short) 0));
        map.dropItemsFromMonster(drops, member, monster);
        check(map.getItems().size() == 1, "one meso drop");
        MapleMapItem boostedDrop = (MapleMapItem) map.getItems().get(0);
        check(boostedDrop.getMeso() == 302 && boostedDrop.getOwnerId() == member.getId()
                && boostedDrop.getDropType() == 1,
                "owner's MESOUP truncates before meso rate; party ownership preserved");
        map.dropItemsFromMonster(drops, outsider, monster);
        check(map.getItems().size() == 2 && map.getItems().stream()
                .map(item -> (MapleMapItem) item).anyMatch(item -> item.getMeso() == 202)
                && outsider.getBuffedValue(MapleBuffStat.MESOUP) == null,
                "unbuffed owner has no multiplier");
        System.out.println("MesoUpTest passed");
    }
}
