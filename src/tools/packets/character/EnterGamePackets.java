package tools.packets.character;

import client.BuddylistEntry;
import client.MapleCharacter;
import client.MapleFamilyEntitlement;
import client.MapleFamilyEntry;
import com.google.flatbuffers.FlatBufferBuilder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import protocol.generated.maple.character.BuddyEntry;
import protocol.generated.maple.character.BuddyListUpdate;
import protocol.generated.maple.character.FamilyEntitlementUse;
import protocol.generated.maple.character.FamilyInfo;
import protocol.generated.maple.character.FamilyPrivilege;
import protocol.generated.maple.character.FamilyPrivilegeList;
import protocol.generated.maple.character.GenderUpdate;
import protocol.generated.maple.character.MemoNote;
import protocol.generated.maple.character.NotesList;
import protocol.generated.maple.character.ReportStatus;
import protocol.generated.maple.character.SkillUpdate;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import tools.MaplePacketCreator;

public final class EnterGamePackets {
    private EnterGamePackets() {
    }

    private static byte[] finish(FlatBufferBuilder builder, int message, int type) {
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, message);
        Packet.addServerMessageType(builder, (byte) type);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }

    public static byte[] buddyList(Collection<BuddylistEntry> buddylist) {
        System.out.println("[FlatBuffers->updateBuddylist] BuddyCount: " + buddylist.size());
        FlatBufferBuilder builder = new FlatBufferBuilder(256);
        int[] offsets = new int[buddylist.size()];
        int n = 0;
        for (BuddylistEntry buddy : buddylist) {
            if (!buddy.isVisible()) {
                continue;
            }
            int name = builder.createString(buddy.getName());
            int group = builder.createString(buddy.getGroup());
            offsets[n++] = BuddyEntry.createBuddyEntry(builder, buddy.getCharacterId(), name, buddy.getChannel() - 1, group);
        }
        int[] used = java.util.Arrays.copyOf(offsets, n);
        int vec = BuddyListUpdate.createBuddiesVector(builder, used);
        int msg = BuddyListUpdate.createBuddyListUpdate(builder, vec);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_BuddyListUpdate);
    }

    public static byte[] familyPrivileges(MapleCharacter player) {
        System.out.println("[FlatBuffers->loadFamily] CharName: " + player.getName());
        FlatBufferBuilder builder = new FlatBufferBuilder(512);
        MapleFamilyEntitlement[] values = MapleFamilyEntitlement.values();
        int[] offsets = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int name = builder.createString(values[i].getName());
            int desc = builder.createString(values[i].getDescription());
            offsets[i] = FamilyPrivilege.createFamilyPrivilege(builder, i <= 1 ? 1 : 2, values[i].getRepCost(), values[i].getUsageLimit(), name, desc);
        }
        int vec = FamilyPrivilegeList.createPrivilegesVector(builder, offsets);
        int msg = FamilyPrivilegeList.createFamilyPrivilegeList(builder, vec);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_FamilyPrivilegeList);
    }

    public static byte[] familyInfo(MapleFamilyEntry f) {
        System.out.println("[FlatBuffers->getFamilyInfo] FamilyEntry: " + (f != null ? f.getName() : "null"));
        FlatBufferBuilder builder = new FlatBufferBuilder(256);
        int msg;
        if (f == null) {
            int empty = builder.createString("");
            FamilyInfo.startFamilyInfo(builder);
            FamilyInfo.addHasEntry(builder, false);
            FamilyInfo.addJuniorsAllowed(builder, (short) 2);
            FamilyInfo.addFamilyName(builder, empty);
            FamilyInfo.addMessage(builder, empty);
            msg = FamilyInfo.endFamilyInfo(builder);
        } else {
            MapleFamilyEntitlement[] values = MapleFamilyEntitlement.values();
            int[] uses = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                uses[i] = FamilyEntitlementUse.createFamilyEntitlementUse(builder, values[i].ordinal(), f.isEntitlementUsed(values[i]) ? 1 : 0);
            }
            int vec = FamilyInfo.createEntitlementsVector(builder, uses);
            int name = builder.createString(f.getFamily().getName());
            int message = builder.createString(f.getFamily().getMessage());
            FamilyInfo.startFamilyInfo(builder);
            FamilyInfo.addHasEntry(builder, true);
            FamilyInfo.addCurRep(builder, f.getReputation());
            FamilyInfo.addTotRep(builder, f.getTotalReputation());
            FamilyInfo.addTodaysRep(builder, f.getTodaysRep());
            FamilyInfo.addJuniorsAdded(builder, (short) f.getJuniorCount());
            FamilyInfo.addJuniorsAllowed(builder, (short) 2);
            FamilyInfo.addLeaderId(builder, f.getFamily().getLeader().getChrId());
            FamilyInfo.addFamilyName(builder, name);
            FamilyInfo.addMessage(builder, message);
            FamilyInfo.addEntitlements(builder, vec);
            msg = FamilyInfo.endFamilyInfo(builder);
        }
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_FamilyInfo);
    }

    public static byte[] notes(ResultSet notes, int count) throws SQLException {
        System.out.println("[FlatBuffers->showNotes] Count: " + count);
        FlatBufferBuilder builder = new FlatBufferBuilder(256);
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            int from = builder.createString(notes.getString("from") + " ");
            int message = builder.createString(notes.getString("message"));
            offsets[i] = MemoNote.createMemoNote(builder, notes.getInt("id"), from, message,
                    MaplePacketCreator.getTime(notes.getLong("timestamp")), notes.getByte("fame"));
            notes.next();
        }
        int vec = NotesList.createNotesVector(builder, offsets);
        int msg = NotesList.createNotesList(builder, vec);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_NotesList);
    }

    public static byte[] gender(MapleCharacter chr) {
        System.out.println("[FlatBuffers->updateGender] CharName: " + chr.getName() + ", Gender: " + chr.getGender());
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = GenderUpdate.createGenderUpdate(builder, chr.getGender());
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_GenderUpdate);
    }

    public static byte[] reportStatus() {
        System.out.println("[FlatBuffers->enableReport]");
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = ReportStatus.createReportStatus(builder, true);
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_ReportStatus);
    }

    public static byte[] skillUpdate(int skillid, int level, int masterlevel, long expiration) {
        System.out.println("[FlatBuffers->updateSkill] SkillId: " + skillid + ", Level: " + level + ", MasterLevel: " + masterlevel);
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = SkillUpdate.createSkillUpdate(builder, skillid, level, masterlevel, MaplePacketCreator.getTime(expiration));
        return finish(builder, msg, ServerMessage.protocol_generated_maple_character_SkillUpdate);
    }
}
