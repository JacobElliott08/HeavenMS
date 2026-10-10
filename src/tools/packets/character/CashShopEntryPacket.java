package tools.packets.character;

import java.util.List;

import client.MapleClient;
import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.CashShopEntry;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.CashShop.CashItemFactory;
import server.CashShop.SpecialCashItem;
import tools.data.output.MaplePacketLittleEndianWriter;

/**
 * Builds the FlatBuffers cash-shop-entry message that replaces the legacy
 * MaplePacketCreator.openCashShop byte stream (SET_ITC / SET_CASH_SHOP).
 * The character-info portion reuses CharacterLoadPacket.buildCharacterInfo;
 * the remaining cash-shop-specific bytes (special items, best-seller lists,
 * etc.) are kept byte-identical to the legacy writer inside an opaque
 * legacy_extra vector, since the client only skips over that data today.
 */
public class CashShopEntryPacket {

    public static byte[] cashShopEntry(MapleClient c, boolean mts) throws Exception {
        FlatBufferBuilder builder = new FlatBufferBuilder(4096);

        int character = CharacterLoadPacket.buildCharacterInfo(builder, c.getPlayer());
        int accountName = builder.createString(c.getAccountName());
        int legacyExtra = CashShopEntry.createLegacyExtraVector(builder, buildLegacyExtra(c, mts));

        CashShopEntry.startCashShopEntry(builder);
        CashShopEntry.addCharacter(builder, character);
        CashShopEntry.addMts(builder, mts);
        CashShopEntry.addAccountName(builder, accountName);
        CashShopEntry.addLegacyExtra(builder, legacyExtra);
        int entry = CashShopEntry.endCashShopEntry(builder);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, entry);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_character_CashShopEntry);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        System.out.println("[FlatBuffers->CashShopEntry] bytes=" + builder.sizedByteArray().length);
        return builder.sizedByteArray();
    }

    private static byte[] buildLegacyExtra(MapleClient c, boolean mts) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        if (mts) {
            mplew.write(new byte[]{(byte) 0x88, 19, 0, 0, 7, 0, 0, 0, (byte) 0xF4, 1, 0, 0, (byte) 0x18, 0, 0, 0, (byte) 0xA8, 0, 0, 0, (byte) 0x70, (byte) 0xAA, (byte) 0xA7, (byte) 0xC5, (byte) 0x4E, (byte) 0xC1, (byte) 0xCA, 1});
        } else {
            mplew.writeInt(0);
            List<SpecialCashItem> lsci = CashItemFactory.getSpecialCashItems();
            mplew.writeShort(lsci.size());//Guess what
            for (SpecialCashItem sci : lsci) {
                mplew.writeInt(sci.getSN());
                mplew.writeInt(sci.getModifier());
                mplew.write(sci.getInfo());
            }
            mplew.skip(121);

            List<List<Integer>> mostSellers = c.getWorldServer().getMostSellerCashItems();
            for (int i = 1; i <= 8; i++) {
                List<Integer> mostSellersTab = mostSellers.get(i);

                for (int j = 0; j < 2; j++) {
                    for (Integer snid : mostSellersTab) {
                        mplew.writeInt(i);
                        mplew.writeInt(j);
                        mplew.writeInt(snid);
                    }
                }
            }

            mplew.writeInt(0);
            mplew.writeShort(0);
            mplew.write(0);
            mplew.writeInt(75);
        }

        return mplew.getPacket();
    }
}
