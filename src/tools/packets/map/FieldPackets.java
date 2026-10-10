package tools.packets.map;

import com.google.flatbuffers.FlatBufferBuilder;
import java.util.Map;
import java.util.Set;
import protocol.generated.maple.map.EnvironmentMoveList;
import protocol.generated.maple.map.EnvironmentState;
import protocol.generated.maple.map.NpcControl;
import protocol.generated.maple.map.NpcQuestData;
import protocol.generated.maple.map.NpcRemove;
import protocol.generated.maple.map.NpcSpawn;
import protocol.generated.maple.map.NpcQuestMarker;
import protocol.generated.maple.network.Ping;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class FieldPackets {
    private FieldPackets() {
    }

    private static byte[] finish(FlatBufferBuilder builder, int message, int type) {
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, message);
        Packet.addServerMessageType(builder, (byte) type);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        byte[] bytes = builder.sizedByteArray();
        System.out.println("[FlatBuffers->" + ServerMessage.name(type) + "] bytes=" + bytes.length);
        return bytes;
    }

    public static byte[] npcSpawn(int oid, int npcId, int x, int cy, boolean flip, int fh, int rx0, int rx1, boolean controlled, boolean minimap) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = NpcSpawn.createNpcSpawn(builder, oid, npcId, (short) x, (short) cy, flip, (short) fh, (short) rx0, (short) rx1, controlled, minimap);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_map_NpcSpawn);
    }

    public static byte[] npcRemove(int oid) {
        FlatBufferBuilder builder = new FlatBufferBuilder(32);
        int msg = NpcRemove.createNpcRemove(builder, oid);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_map_NpcRemove);
    }

    public static byte[] npcControl(int oid, boolean controlled) {
        FlatBufferBuilder builder = new FlatBufferBuilder(32);
        int msg = NpcControl.createNpcControl(builder, oid, controlled);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_map_NpcControl);
    }

    public static byte[] ping() {
        FlatBufferBuilder builder = new FlatBufferBuilder(32);
        Ping.startPing(builder);
        int msg = Ping.endPing(builder);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_network_Ping);
    }

    public static byte[] npcQuestData(Map<Integer, Integer> npcQuestData) {
        FlatBufferBuilder builder = new FlatBufferBuilder(256);
        int[] offsets = new int[npcQuestData.size()];
        int i = 0;
        for (Map.Entry<Integer, Integer> entry : npcQuestData.entrySet()) {
            System.out.println("[FlatBuffers->NpcQuestData] NPC ID - " + entry.getKey() + " STATUS " + entry.getValue());
            offsets[i++] = NpcQuestMarker.createNpcQuestMarker(builder, entry.getKey(), entry.getValue());
        }
        int vec = NpcQuestData.createNpcsVector(builder, offsets);
        int msg = NpcQuestData.createNpcQuestData(builder, vec);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_map_NpcQuestData);
    }

    public static byte[] environmentMoveList(Set<Map.Entry<String, Integer>> envList) {
        FlatBufferBuilder builder = new FlatBufferBuilder(256);
        int[] offsets = new int[envList.size()];
        int i = 0;
        for (Map.Entry<String, Integer> env : envList) {
            int name = builder.createString(env.getKey());
            offsets[i++] = EnvironmentState.createEnvironmentState(builder, name, env.getValue());
        }
        int vec = EnvironmentMoveList.createEnvironmentsVector(builder, offsets);
        int msg = EnvironmentMoveList.createEnvironmentMoveList(builder, vec);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_map_EnvironmentMoveList);
    }
}
