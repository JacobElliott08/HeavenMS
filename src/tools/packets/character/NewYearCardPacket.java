package tools.packets.character;

import client.newyear.NewYearCardRecord;
import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.NewYearCard;
import protocol.generated.maple.character.NewYearCardResponse;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class NewYearCardPacket {
    private NewYearCardPacket() {
    }

    public static byte[] response(int userId, NewYearCardRecord newyear, int mode, int msg) {
        FlatBufferBuilder builder = new FlatBufferBuilder(192);

        int cardOffset = 0;
        int cardId = 0;
        int senderNameOffset = 0;
        int broadcasterId = 0;
        int unreceivedSenderId = 0;
        int unreceivedSenderNameOffset = 0;

        switch (mode) {
            case 4:  // Successfully sent a New Year Card
            case 6:  // Successfully received a New Year Card
                cardOffset = createCard(builder, newyear);
                break;

            case 8:  // Successfully deleted a New Year Card
                cardId = newyear.getId();
                break;

            case 0xC: // NotiArrived
                cardId = newyear.getId();
                senderNameOffset = builder.createString(newyear.getSenderName());
                break;

            case 0xD: // BroadCast_AddCardInfo
                cardId = newyear.getId();
                broadcasterId = userId;
                break;

            case 0xE: // BroadCast_RemoveCardInfo
                cardId = newyear.getId();
                break;

            case 0xA: // GetUnreceivedList_Done (legacy hardcodes a single entry)
                cardId = newyear.getId();
                unreceivedSenderId = newyear.getSenderId();
                unreceivedSenderNameOffset = builder.createString(newyear.getSenderName());
                break;

            default: // 5, 7, 9, 0xB — generic error/status codes carried in msg
                break;
        }

        int response = NewYearCardResponse.createNewYearCardResponse(
                builder, mode, msg, cardOffset, cardId, senderNameOffset, broadcasterId, unreceivedSenderId, unreceivedSenderNameOffset);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, response);
        Packet.addServerMessageType(builder, (byte) ServerMessage.protocol_generated_maple_character_NewYearCardResponse);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        System.out.println("[FlatBuffers->NewYearCardResponse] bytes=" + builder.sizedByteArray().length);
        return builder.sizedByteArray();
    }

    private static int createCard(FlatBufferBuilder builder, NewYearCardRecord newyear) {
        int senderNameOffset = builder.createString(newyear.getSenderName());
        int receiverNameOffset = builder.createString(newyear.getReceiverName());
        int messageOffset = builder.createString(newyear.getMessage());
        return NewYearCard.createNewYearCard(
                builder,
                newyear.getId(),
                newyear.getSenderId(),
                senderNameOffset,
                newyear.isSenderCardDiscarded(),
                newyear.getDateSent(),
                newyear.getReceiverId(),
                receiverNameOffset,
                newyear.isReceiverCardDiscarded(),
                newyear.isReceiverCardReceived(),
                newyear.getDateReceived(),
                messageOffset);
    }
}
