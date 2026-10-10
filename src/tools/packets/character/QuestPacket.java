package tools.packets.character;

import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.QuestActionResult;
import protocol.generated.maple.character.QuestInfoUpdate;
import protocol.generated.maple.character.QuestInfoUpdateKind;
import protocol.generated.maple.character.QuestStatusUpdate;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class QuestPacket {
    private QuestPacket() {
    }

    public static byte[] status(short questId, int status, String progress, long completionTime) {
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        int progressOffset = progress == null || progress.isEmpty() ? 0 : builder.createString(progress);
        QuestStatusUpdate.startQuestStatusUpdate(builder);
        QuestStatusUpdate.addQuestId(builder, questId);
        QuestStatusUpdate.addStatus(builder, (byte) status);
        if (progressOffset != 0) {
            QuestStatusUpdate.addProgress(builder, progressOffset);
        }
        QuestStatusUpdate.addCompletionTime(builder, completionTime);
        int update = QuestStatusUpdate.endQuestStatusUpdate(builder);
        return packet(builder, update, (byte) ServerMessage.protocol_generated_maple_character_QuestStatusUpdate);
    }

    public static byte[] infoNpc(short questId, int npcId) {
        return info(questId, (byte) QuestInfoUpdateKind.Npc, npcId, 0);
    }

    public static byte[] infoTimeLimitAdd(short questId, int timeLimit) {
        return info(questId, (byte) QuestInfoUpdateKind.TimeLimitAdd, 0, timeLimit);
    }

    public static byte[] infoTimeLimitRemove(short questId) {
        return info(questId, (byte) QuestInfoUpdateKind.TimeLimitRemove, 0, 0);
    }

    private static byte[] info(short questId, byte kind, int npcId, int timeLimit) {
        FlatBufferBuilder builder = new FlatBufferBuilder(96);
        QuestInfoUpdate.startQuestInfoUpdate(builder);
        QuestInfoUpdate.addQuestId(builder, questId);
        QuestInfoUpdate.addKind(builder, kind);
        QuestInfoUpdate.addNpcId(builder, npcId);
        QuestInfoUpdate.addTimeLimit(builder, timeLimit);
        QuestInfoUpdate.addPosition(builder, (short) 1);
        int update = QuestInfoUpdate.endQuestInfoUpdate(builder);
        return packet(builder, update, (byte) ServerMessage.protocol_generated_maple_character_QuestInfoUpdate);
    }

    public static byte[] error(short questId) {
        return result(questId, 0, 0, 0, (short) 0);
    }

    public static byte[] failure(int code) {
        return result((short) 0, 1, code, 0, (short) 0);
    }

    public static byte[] expired(short questId) {
        return result(questId, 2, 0, 0, (short) 0);
    }

    public static byte[] finish(short questId, int npcId, short nextQuestId) {
        return result(questId, 3, 0, npcId, nextQuestId);
    }

    public static byte[] completionEffect(short questId) {
        return result(questId, 4, 0, 0, (short) 0);
    }

    private static byte[] result(short questId, int result, int code, int npcId, short next) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = QuestActionResult.createQuestActionResult(builder, questId, (byte) result, (byte) code, npcId, next);
        return packet(builder, msg, (byte) ServerMessage.protocol_generated_maple_character_QuestActionResult);
    }

    private static byte[] packet(FlatBufferBuilder builder, int message, byte messageType) {
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, message);
        Packet.addServerMessageType(builder, messageType);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        byte[] out = builder.sizedByteArray();
        System.out.println("[FlatBuffers->" + ServerMessage.name(messageType) + "] bytes=" + out.length);
        return out;
    }
}