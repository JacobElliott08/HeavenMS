package tools.packets.character;

import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.StatusInfo;
import protocol.generated.maple.character.StatusInfoType;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class StatusInfoPackets {
    private StatusInfoPackets() {
    }

    private static byte[] build(String name, String detail, byte type, int itemId, int amount, boolean white,
            boolean inChat, int bonusEvent, int partyBonus, int equipBonus, int areaId, String text) {
        System.out.println("[FlatBuffers->" + name + "] " + detail);
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        int textOffset = text != null ? builder.createString(text) : 0;
        StatusInfo.startStatusInfo(builder);
        StatusInfo.addType(builder, type);
        StatusInfo.addItemId(builder, itemId);
        StatusInfo.addAmount(builder, amount);
        StatusInfo.addWhite(builder, white);
        StatusInfo.addInChat(builder, inChat);
        StatusInfo.addBonusEvent(builder, bonusEvent);
        StatusInfo.addPartyBonus(builder, partyBonus);
        StatusInfo.addEquipBonus(builder, equipBonus);
        StatusInfo.addAreaId(builder, (short) areaId);
        if (text != null) {
            StatusInfo.addText(builder, textOffset);
        }
        int msg = StatusInfo.endStatusInfo(builder);
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, msg);
        Packet.addServerMessageType(builder, (byte) ServerMessage.protocol_generated_maple_character_StatusInfo);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }

    public static byte[] itemGain(int itemId, int quantity, boolean inChat) {
        return build("itemGain", "ItemId: " + itemId + ", Quantity: " + quantity + ", InChat: " + inChat, StatusInfoType.ItemGain, itemId, quantity, false, inChat, 0, 0, 0, 0, null);
    }

    public static byte[] inventoryFull() {
        return build("inventoryFull", "", StatusInfoType.InventoryFull, 0, 0, false, false, 0, 0, 0, 0, null);
    }

    public static byte[] itemUnavailable() {
        return build("itemUnavailable", "", StatusInfoType.ItemUnavailable, 0, 0, false, false, 0, 0, 0, 0, null);
    }

    public static byte[] mesoGain(int gain, boolean inChat) {
        return build("mesoGain", "Gain: " + gain + ", InChat: " + inChat, inChat ? StatusInfoType.MesoGainChat : StatusInfoType.MesoGain, 0, gain, false, inChat, 0, 0, 0, 0, null);
    }

    public static byte[] itemExpired(int itemId) {
        return build("itemExpired", "ItemId: " + itemId, StatusInfoType.ItemExpired, itemId, 0, false, false, 0, 0, 0, 0, null);
    }

    public static byte[] expGain(int gain, int equip, int party, boolean inChat, boolean white) {
        return build("expGain", "Gain: " + gain + ", Equip: " + equip + ", Party: " + party + ", InChat: " + inChat + ", White: " + white,
                StatusInfoType.ExpGain, 0, gain, white, inChat, 0, party, equip, 0, null);
    }

    public static byte[] fameGain(int gain) {
        return build("fameGain", "Gain: " + gain, StatusInfoType.FameGain, 0, gain, false, false, 0, 0, 0, 0, null);
    }

    public static byte[] guildPoints(int gp) {
        return build("guildPoints", "GpChange: " + gp, StatusInfoType.GuildPoints, 0, gp, false, false, 0, 0, 0, 0, null);
    }

    public static byte[] itemMessage(int itemId) {
        return build("itemMessage", "ItemId: " + itemId, StatusInfoType.ItemMessage, itemId, 0, false, false, 0, 0, 0, 0, null);
    }

    public static byte[] infoText(String text) {
        return build("infoText", "Text: " + text, StatusInfoType.Text, 0, 0, false, false, 0, 0, 0, 0, text);
    }

    public static byte[] areaInfo(int area, String info) {
        return build("areaInfo", "Area: " + area + ", Info: " + info, StatusInfoType.AreaInfo, 0, 0, false, false, 0, 0, 0, area, info);
    }
}
