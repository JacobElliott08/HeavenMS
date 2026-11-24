package tools.packets.map;

import client.status.MonsterStatus;
import com.google.flatbuffers.FlatBufferBuilder;
import java.util.Collection;
import protocol.generated.maple.map.MobStatusOperation;
import protocol.generated.maple.map.MobStatusUpdate;
import protocol.generated.maple.map.MobStatusUpdateEntry;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

/** Server-to-client MSFB payloads; Mina supplies framing and encryption. */
public final class MobStatusPackets {
    private MobStatusPackets() {}

    public record Entry(MonsterStatus status, int value, long durationMs, int sourceSkillId) {}

    public static byte[] update(int mapId, int mobOid, int operation, Collection<Entry> statuses) {
        return update(mapId, mobOid, operation, statuses, false);
    }

    public static byte[] update(int mapId, int mobOid, int operation, Collection<Entry> statuses, boolean snapshot) {
        if (mobOid <= 0 || statuses.isEmpty()
                || (operation != MobStatusOperation.Apply && operation != MobStatusOperation.Cancel)) {
            throw new IllegalArgumentException("Invalid mob status update");
        }
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        int[] entries = new int[statuses.size()];
        int i = 0;
        for (Entry entry : statuses) {
            int name = builder.createString(entry.status().name());
            entries[i++] = MobStatusUpdateEntry.createMobStatusUpdateEntry(builder, name,
                    entry.value(), entry.durationMs(), entry.sourceSkillId());
        }
        int vector = MobStatusUpdate.createEntriesVector(builder, entries);
        int update = MobStatusUpdate.createMobStatusUpdate(builder, mapId, mobOid, operation, vector, snapshot);
        Packet.startPacket(builder);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_map_MobStatusUpdate);
        Packet.addServerMessage(builder, update);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }
}
