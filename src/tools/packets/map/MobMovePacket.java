package tools.packets.map;

import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.map.MobMoveResponse;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class MobMovePacket {
    private MobMovePacket() {
    }

    public static byte[] moveResponse(int objectId, short moveId, int currentMp, boolean useSkills, int skillId, int skillLevel) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int response = MobMoveResponse.createMobMoveResponse(
                builder, objectId, moveId, useSkills, (short) currentMp, skillId, skillLevel);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, response);
        Packet.addServerMessageType(builder, (byte) ServerMessage.protocol_generated_maple_map_MobMoveResponse);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        System.out.println("[FlatBuffers->MobMoveResponse] bytes=" + builder.sizedByteArray().length);
        return builder.sizedByteArray();
    }
}
