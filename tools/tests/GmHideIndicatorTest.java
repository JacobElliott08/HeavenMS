import client.MapleBuffStat;
import client.MapleCharacter;
import client.MapleClient;
import client.SkillFactory;
import client.command.commands.gm2.HideCommand;
import client.command.commands.gm2.UnHideCommand;
import config.YamlConfig;
import constants.skills.SuperGM;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import protocol.generated.maple.character.BuffApply;
import protocol.generated.maple.character.BuffCancel;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.maps.MapleMap;

/** Offline: actual Hide state, commands, skill effect and MSFB indicator packets. */
public class GmHideIndicatorTest {
    static class Client extends MapleClient {
        final List<byte[]> packets = new ArrayList<>();
        Client() { super(null, null, null); }
        @Override public void announce(byte[] bytes) { packets.add(bytes); }
    }
    static class MapFixture extends MapleMap {
        int nonGmRemovals, gmApplies, gmCancels, spawns;
        MapFixture() { super(100000000, 0, 1, 100000000, 1); }
        @Override public void broadcastNONGMMessage(MapleCharacter chr, byte[] packet, boolean repeat) { nonGmRemovals++; }
        @Override public void broadcastGMMessage(MapleCharacter chr, byte[] packet, boolean repeat) {
            Packet p = Packet.getRootAsPacket(ByteBuffer.wrap(packet));
            if (p.serverMessageType() == ServerMessage.protocol_generated_maple_character_ForeignBuffApply) gmApplies++;
            if (p.serverMessageType() == ServerMessage.protocol_generated_maple_character_ForeignBuffCancel) gmCancels++;
        }
        @Override public void broadcastSpawnPlayerMapObjectMessage(MapleCharacter source, MapleCharacter player, boolean entering) { spawns++; }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void set(MapleCharacter chr, String name, Object value) throws Exception {
        for (Class<?> type = MapleCharacter.class; type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.set(chr, value);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    static void indicator(Client client, boolean hidden) {
        int found = 0;
        for (byte[] bytes : client.packets) {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            if (bytes.length < 8 || !Packet.PacketBufferHasIdentifier(data)) continue;
            Packet p = Packet.getRootAsPacket(data);
            if (p.serverMessageType() == ServerMessage.protocol_generated_maple_character_BuffApply) {
                BuffApply b = new BuffApply(); p.serverMessage(b);
                check(hidden && b.sourceId() == SuperGM.HIDE && b.statupsLength() == 0
                        && b.durationMs() == Integer.MAX_VALUE, "stat-free hide apply");
                found++;
            } else if (p.serverMessageType() == ServerMessage.protocol_generated_maple_character_BuffCancel) {
                BuffCancel b = new BuffCancel(); p.serverMessage(b);
                check(!hidden && b.sourceId() == SuperGM.HIDE, "hide cancel");
                found++;
            }
        }
        check(found == 1, "one local indicator packet: " + found);
        client.packets.clear();
    }
    public static void main(String[] args) throws Exception {
        YamlConfig.config.server.USE_THREAD_TRACKER = false;
        Method isolation = ClawAttackTransportTest.class.getDeclaredMethod("isolateDatabase");
        isolation.setAccessible(true); isolation.invoke(null);
        SkillFactory.loadAllSkills();
        Constructor<MapleCharacter> ctor = MapleCharacter.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MapleCharacter chr = ctor.newInstance();
        MapFixture map = new MapFixture();
        Client c = new Client(); chr.setClient(c); c.setPlayer(chr);
        set(chr, "map", map); set(chr, "id", 1); set(chr, "gmLevel", 2);
        chr.setPosition(new Point(0, 0));

        chr.Hide(true);
        check(chr.isHidden() && map.nonGmRemovals == 1 && map.gmApplies == 1, "hide lifecycle");
        indicator(c, true);
        check(chr.getBuffedValue(MapleBuffStat.DARKSIGHT) == null, "no thief buff");
        chr.Hide(true);
        check(c.packets.isEmpty(), "idempotent hide");

        // A fresh client after CharacterLoad receives a snapshot of retained state.
        Client reconnected = new Client(); chr.setClient(reconnected); reconnected.setPlayer(chr);
        chr.syncHideIndicator();
        indicator(reconnected, true);
        chr.Hide(false);
        check(!chr.isHidden() && map.gmCancels == 1 && map.spawns == 1, "unhide lifecycle");
        indicator(reconnected, false);
        chr.syncHideIndicator(); indicator(reconnected, false);

        new HideCommand().execute(reconnected, new String[0]);
        check(chr.isHidden(), "hide command"); indicator(reconnected, true);
        new UnHideCommand().execute(reconnected, new String[0]);
        check(!chr.isHidden(), "unhide command"); indicator(reconnected, false);
        SkillFactory.getSkill(SuperGM.HIDE).getEffect(1).applyTo(chr);
        check(chr.isHidden(), "skill toggle"); indicator(reconnected, true);
        chr.Hide(false); indicator(reconnected, false);
        // Same toggle entry point used by login auto-hide.
        chr.toggleHide(true);
        check(chr.isHidden(), "auto-hide toggle"); indicator(reconnected, true);
        chr.Hide(false); indicator(reconnected, false);
        set(chr, "gmLevel", 0);
        chr.Hide(true);
        check(!chr.isHidden() && reconnected.packets.isEmpty(), "non-GM cannot hide");
        System.out.println("GmHideIndicatorTest passed");
    }
}
