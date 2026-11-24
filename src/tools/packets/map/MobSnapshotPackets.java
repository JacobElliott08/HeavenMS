package tools.packets.map;

import com.google.flatbuffers.FlatBufferBuilder;
import java.awt.Point;
import protocol.generated.maple.map.MobAuthority;
import protocol.generated.maple.map.MobSnapshot;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.life.GroundMobSimulation;

public final class MobSnapshotPackets {
    private MobSnapshotPackets() {}

    public static byte[] snapshot(int mapId, int objectId, long instance, long tick,
                                  boolean authoritative, Point p, int foothold, int stance, boolean alive) {
        FlatBufferBuilder b = new FlatBufferBuilder(128);
        int state = MobSnapshot.createMobSnapshot(b, mapId, objectId, instance, tick,
                authoritative ? MobAuthority.ServerGround : MobAuthority.Legacy,
                (short) p.x, (short) p.y, foothold, stance, GroundMobSimulation.INTERVAL_MS, alive);
        Packet.startPacket(b);
        Packet.addServerMessageType(b, ServerMessage.protocol_generated_maple_map_MobSnapshot);
        Packet.addServerMessage(b, state);
        Packet.finishPacketBuffer(b, Packet.endPacket(b));
        return b.sizedByteArray();
    }
}
