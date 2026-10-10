package tools;

import net.opcodes.SendOpcode;
import server.life.MapleNPC;
import tools.data.output.MaplePacketLittleEndianWriter;

/**
 * Legacy NPC packet creation methods using the old NPC_TALK protocol (0x130).
 * 
 * This class contains backward compatibility support for existing code.
 * New code should use ReplyBuilder for the NPC_DIALOGUE protocol (0x200) instead.
 */
public class LegacyNPCPackets {
    
    /**
     * Creates a legacy NPC_TALK packet (0x130).
     * 
     * Speaker values:
     * 0: NPC talking (left)
     * 1: NPC talking (right)
     * 2: Player talking (left)
     * 3: Player talking (right)
     *
     * @param npc NPC id
     * @param msgType Message type
     * @param talk Dialogue text
     * @param endBytes End bytes (navigation)
     * @param speaker Speaker position
     * @return Legacy NPC_TALK packet
     */
    public static byte[] getNPCTalk(int npc, byte msgType, String talk, String endBytes, byte speaker) {
        System.out.println("[LEGACY WARNING] Using deprecated NPC_TALK protocol (0x130) for NPC: " + npc);
        System.out.println("[LegacyNPCPackets->getNPCTalk] Npc: " + npc + ", MsgType: " + msgType + ", Speaker: " + speaker);
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendOpcode.NPC_TALK.getValue());
        mplew.write(4);
        mplew.writeInt(npc);
        mplew.write(msgType);
        mplew.write(speaker);
        mplew.writeMapleAsciiString(talk);
        mplew.write(HexTool.getByteArrayFromHexString(endBytes));
        return mplew.getPacket();
    }
    
    public static byte[] getNPCTalk(MapleNPC npc, byte msgType, String talk, String endBytes, byte speaker) {
        return getNPCTalk(npc.getId(), msgType, talk, endBytes, speaker);
    }
    
    /**
     * Creates a legacy NPC_TALK packet for style selection (0x130).
     */
    public static byte[] getNPCTalkStyle(int npc, String talk, int styles[]) {
        System.out.println("[LEGACY WARNING] Using deprecated NPC_TALK protocol (0x130) for style selection - NPC: " + npc);
        System.out.println("[LegacyNPCPackets->getNPCTalkStyle] Npc: " + npc + ", StyleCount: " + styles.length);
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendOpcode.NPC_TALK.getValue());
        mplew.write(4);
        mplew.writeInt(npc);
        mplew.write(7);
        mplew.write(0);
        mplew.writeMapleAsciiString(talk);
        mplew.write(styles.length);
        for (int i = 0; i < styles.length; i++) {
            mplew.writeInt(styles[i]);
        }
        return mplew.getPacket();
    }
    
    public static byte[] getNPCTalkStyle(MapleNPC npc, String talk, int styles[]) {
        return getNPCTalkStyle(npc.getId(), talk, styles);
    }
    
    /**
     * Creates a legacy NPC_TALK packet for number input (0x130).
     */
    public static byte[] getNPCTalkNum(int npc, String talk, int def, int min, int max) {
        System.out.println("[LEGACY WARNING] Using deprecated NPC_TALK protocol (0x130) for number input - NPC: " + npc);
        System.out.println("[LegacyNPCPackets->getNPCTalkNum] Npc: " + npc + ", Def: " + def + ", Min: " + min + ", Max: " + max);
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendOpcode.NPC_TALK.getValue());
        mplew.write(4);
        mplew.writeInt(npc);
        mplew.write(3);
        mplew.write(0);
        mplew.writeMapleAsciiString(talk);
        mplew.writeInt(def);
        mplew.writeInt(min);
        mplew.writeInt(max);
        mplew.writeInt(0);
        return mplew.getPacket();
    }
    
    public static byte[] getNPCTalkNum(MapleNPC npc, String talk, int def, int min, int max) {
        return getNPCTalkNum(npc.getId(), talk, def, min, max);
    }
    
    /**
     * Creates a legacy NPC_TALK packet for text input (0x130).
     */
    public static byte[] getNPCTalkText(int npc, String talk, String def) {
        System.out.println("[LEGACY WARNING] Using deprecated NPC_TALK protocol (0x130) for text input - NPC: " + npc);
        System.out.println("[LegacyNPCPackets->getNPCTalkText] Npc: " + npc + ", Def: " + def);
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendOpcode.NPC_TALK.getValue());
        mplew.write(4);
        mplew.writeInt(npc);
        mplew.write(2);
        mplew.write(0);
        mplew.writeMapleAsciiString(talk);
        mplew.writeMapleAsciiString(def);
        mplew.writeInt(0);
        return mplew.getPacket();
    }
    
    public static byte[] getNPCTalkText(MapleNPC npc, String talk, String def) {
        return getNPCTalkText(npc.getId(), talk, def);
    }
}
