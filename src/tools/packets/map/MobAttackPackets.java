package tools.packets.map;

import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.map.MobAttackEvent;
import protocol.generated.maple.map.MobAttackPhase;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.life.GroundMobAttackSimulation.Event;

/** MSFB server-to-client events; Maple's existing codec supplies framing. */
public final class MobAttackPackets {
    private MobAttackPackets() {}

    private static int phase(Event event) {
        return switch (event.phase()) {
            case START -> MobAttackPhase.Start;
            case LAUNCH -> MobAttackPhase.Launch;
            case IMPACT -> MobAttackPhase.Impact;
            case CANCEL -> MobAttackPhase.Cancel;
        };
    }

    public static byte[] event(int mapId, int oid, long instance, Event event, int damage) {
        FlatBufferBuilder b = new FlatBufferBuilder(128);
        int body = MobAttackEvent.createMobAttackEvent(b, mapId, oid, instance, event.attackId(),
                phase(event), event.targetId(), (short) event.from().x, (short) event.from().y,
                (short) event.to().x, (short) event.to().y, event.duration(), event.left(),
                event.collision() && damage >= 0, Math.max(0, damage));
        Packet.startPacket(b);
        Packet.addServerMessageType(b, ServerMessage.protocol_generated_maple_map_MobAttackEvent);
        Packet.addServerMessage(b, body);
        Packet.finishPacketBuffer(b, Packet.endPacket(b));
        return b.sizedByteArray();
    }
}
