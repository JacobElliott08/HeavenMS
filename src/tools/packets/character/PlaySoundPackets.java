package tools.packets.character;

import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.PlaySound;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class PlaySoundPackets {
    private PlaySoundPackets() {
    }

    public static byte[] playSound(String path) {
        System.out.println("[FlatBuffers->playSound] Path: " + path);
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int pathOffset = builder.createString(path);
        int msg = PlaySound.createPlaySound(builder, pathOffset);
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, msg);
        Packet.addServerMessageType(builder, (byte) ServerMessage.protocol_generated_maple_character_PlaySound);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }
}
