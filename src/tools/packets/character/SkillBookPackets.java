package tools.packets.character;

import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.SkillBookUseResult;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class SkillBookPackets {
    private SkillBookPackets() {}

    public static byte[] result(int characterId, int itemId, int skillId, int masterLevel, int status) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int result = SkillBookUseResult.createSkillBookUseResult(
                builder, characterId, itemId, skillId, masterLevel, status);
        Packet.startPacket(builder);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_character_SkillBookUseResult);
        Packet.addServerMessage(builder, result);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }
}
