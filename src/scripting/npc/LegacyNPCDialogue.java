package scripting.npc;

import client.MapleClient;
import tools.LegacyNPCPackets;

/**
 * Legacy NPC dialogue methods using the old NPC_TALK protocol (0x130).
 * 
 * This class contains backward compatibility support for existing NPC scripts.
 * New code should use NPCConversationManager.createReply() -> ReplyBuilder API instead.
 */
public class LegacyNPCDialogue {
    
    private final MapleClient client;
    private final int npcId;
    private boolean warningPrinted = false;
    
    public LegacyNPCDialogue(MapleClient client, int npcId) {
        this.client = client;
        this.npcId = npcId;
    }
    
    private void printLegacyWarning(String methodName) {
        if (!warningPrinted) {
            System.out.println("[LEGACY WARNING] NPC " + npcId + " is using legacy dialogue method: " + methodName);
            System.out.println("[LEGACY WARNING] Please migrate to createReply() -> ReplyBuilder API");
            warningPrinted = true;
        }
    }
    
    public void sendNext(String text) {
        printLegacyWarning("sendNext");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "00 01", (byte) 0));
    }
    
    public void sendPrev(String text) {
        printLegacyWarning("sendPrev");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "01 00", (byte) 0));
    }
    
    public void sendNextPrev(String text) {
        printLegacyWarning("sendNextPrev");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "01 01", (byte) 0));
    }
    
    public void sendOk(String text) {
        printLegacyWarning("sendOk");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "00 00", (byte) 0));
    }
    
    public void sendYesNo(String text) {
        printLegacyWarning("sendYesNo");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 1, text, "", (byte) 0));
    }
    
    public void sendAcceptDecline(String text) {
        printLegacyWarning("sendAcceptDecline");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0x0C, text, "", (byte) 0));
    }
    
    public void sendSimple(String text) {
        printLegacyWarning("sendSimple");
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 4, text, "", (byte) 0));
    }
    
    public void sendNext(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "00 01", speaker));
    }
    
    public void sendPrev(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "01 00", speaker));
    }
    
    public void sendNextPrev(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "01 01", speaker));
    }
    
    public void sendOk(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0, text, "00 00", speaker));
    }
    
    public void sendYesNo(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 1, text, "", speaker));
    }
    
    public void sendAcceptDecline(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 0x0C, text, "", speaker));
    }
    
    public void sendSimple(String text, byte speaker) {
        client.announce(LegacyNPCPackets.getNPCTalk(npcId, (byte) 4, text, "", speaker));
    }
    
    public void sendGetNumber(String text, int def, int min, int max) {
        printLegacyWarning("sendGetNumber");
        client.announce(LegacyNPCPackets.getNPCTalkNum(npcId, text, def, min, max));
    }
    
    public void sendGetText(String text) {
        printLegacyWarning("sendGetText");
        client.announce(LegacyNPCPackets.getNPCTalkText(npcId, text, ""));
    }
    
    public void sendStyle(String text, int styles[]) {
        printLegacyWarning("sendStyle");
        if (styles.length > 0) {
            client.announce(LegacyNPCPackets.getNPCTalkStyle(npcId, text, styles));
        } else {
            // thanks Conrad for noticing empty styles crashing players
            sendOk("Sorry, there are no options of cosmetics available for you here at the moment.");
        }
    }
}
