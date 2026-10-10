package tools.packets.character;

import java.util.List;

import client.MapleCharacter;
import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.character.CharacterList;
import protocol.generated.maple.character.CharacterSelectEntry;
import protocol.generated.maple.character.NewCharacterEntry;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

/**
 * Builds the FlatBuffers char-select messages that replace the legacy
 * MaplePacketCreator.addCharEntry byte stream shared by getCharList
 * (CHARLIST) and addNewCharEntry (ADD_NEWCHAR_ENTRY).
 */
public class CharacterSelectPacket {

    public static byte[] characterList(List<MapleCharacter> chars, int status, int picStatus, int characterSlots) {
        FlatBufferBuilder builder = new FlatBufferBuilder(4096);

        int[] entryOffsets = new int[chars.size()];
        for (int i = 0; i < chars.size(); i++) {
            entryOffsets[i] = buildSelectEntry(builder, chars.get(i));
        }
        int entriesVector = CharacterList.createEntriesVector(builder, entryOffsets);

        CharacterList.startCharacterList(builder);
        CharacterList.addStatus(builder, status);
        CharacterList.addEntries(builder, entriesVector);
        CharacterList.addPicStatus(builder, picStatus);
        CharacterList.addCharacterSlots(builder, characterSlots);
        int list = CharacterList.endCharacterList(builder);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, list);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_character_CharacterList);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        byte[] result = builder.sizedByteArray();
        System.out.println("[FlatBuffers->CharacterList] bytes=" + result.length);
        System.out.println("[LoginFlow][CharList] characters=" + chars.size() + " bytes=" + result.length);
        return result;
    }

    public static byte[] newCharacterEntry(MapleCharacter chr) {
        FlatBufferBuilder builder = new FlatBufferBuilder(1024);

        int entry = buildSelectEntry(builder, chr);

        NewCharacterEntry.startNewCharacterEntry(builder);
        NewCharacterEntry.addEntry(builder, entry);
        int newEntry = NewCharacterEntry.endNewCharacterEntry(builder);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, newEntry);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_character_NewCharacterEntry);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        System.out.println("[FlatBuffers->NewCharacterEntry] bytes=" + builder.sizedByteArray().length);
        return builder.sizedByteArray();
    }

    private static int buildSelectEntry(FlatBufferBuilder builder, MapleCharacter chr) {
        int stats = CharacterLoadPacket.buildStats(builder, chr);
        int look = CharacterLoadPacket.buildLook(builder, chr);
        boolean isGm = chr.isGM() || chr.isGmJob();

        CharacterSelectEntry.startCharacterSelectEntry(builder);
        CharacterSelectEntry.addStats(builder, stats);
        CharacterSelectEntry.addLook(builder, look);
        if (!isGm) {
            CharacterSelectEntry.addHasWorldRank(builder, true);
            CharacterSelectEntry.addWorldRank(builder, chr.getRank());
            CharacterSelectEntry.addWorldRankMove(builder, chr.getRankMove());
            CharacterSelectEntry.addJobRank(builder, chr.getJobRank());
            CharacterSelectEntry.addJobRankMove(builder, chr.getJobRankMove());
        }
        return CharacterSelectEntry.endCharacterSelectEntry(builder);
    }
}
