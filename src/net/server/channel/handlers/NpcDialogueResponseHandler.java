package net.server.channel.handlers;

import client.MapleClient;
import net.AbstractMaplePacketHandler;
import scripting.npc.DialogueType;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestScriptManager;
import server.npc.session.NpcSession;
import server.npc.interaction.NpcInteractionContext;
import server.npc.interaction.NpcInteractionOption;
import server.life.MapleNPC;
import server.maps.MapleMapObject;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;
import protocol.generated.maple.npc.NpcDialogueResponse;

import java.util.List;

/**
 * Handles the {@code NPC_DIALOGUE_RESPONSE} packet (RecvOpcode 0xA0).
 *
 * <p>This handler is the server-side counterpart of {@link scripting.npc.ReplyBuilder}.
 * It reads the new fully-binary response packet and dispatches into the
 * existing {@link NPCScriptManager#action} / {@link QuestScriptManager} pipeline.</p>
 *
 * <h2>Client → Server packet format</h2>
 * <pre>
 *   Offset  Size    Field
 *   ──────  ──────  ──────────────────────────────────────────────────────
 *     0     u16 LE  opcode        = NPC_DIALOGUE_RESPONSE (0xA0)
 *     2     u8      action        0x00=cancel/close  0x01=ok/next/yes/accept
 *                                 0x02=prev/no/decline
 *     3     u8      dialogue_type echoed from the NPC_DIALOGUE server packet
 *     -- conditional payload (only when action != 0x00): --
 *     SELECTION:   [u8  selected_index]
 *     GET_NUMBER:  [s32 LE value]
 *     GET_TEXT:    [u16 LE byte_len][u8[] UTF-8 input]
 * </pre>
 *
 * <h2>Dispatch contract</h2>
 * <p>The handler preserves the {@code action / type / selection} signature
 * of {@link NPCScriptManager#action(MapleClient, byte, byte, int)} to avoid
 * breaking the existing script callback convention.  Selection is mapped as:</p>
 * <ul>
 *   <li>{@code SELECTION}   → the selected index (u8)</li>
 *   <li>{@code GET_NUMBER}  → the numeric value (s32)</li>
 *   <li>{@code GET_TEXT}    → {@code -1}; text stored via {@code cm.setGetText()}</li>
 *   <li>all others          → {@code -1}</li>
 * </ul>
 *
 * <h2>Client-side pseudo-code that produces this packet</h2>
 * <pre>
 *   // Player clicked a selection:
 *   function on_selection_clicked(index):
 *       w = PacketWriter(opcode=0xA0)
 *       w.writeByte(ACTION_OK = 0x01)
 *       w.writeByte(DIALOGUE_SELECTION = 0x07)
 *       w.writeByte(index)
 *       sendPacket(w.finish())
 *
 *   // Player closed the dialogue:
 *   function on_dialogue_closed():
 *       w = PacketWriter(opcode=0xA0)
 *       w.writeByte(ACTION_CANCEL = 0x00)
 *       w.writeByte(last_dialogue_type)
 *       sendPacket(w.finish())
 *
 *   // Player submitted a number:
 *   function on_number_submitted(value):
 *       w = PacketWriter(opcode=0xA0)
 *       w.writeByte(ACTION_OK = 0x01)
 *       w.writeByte(DIALOGUE_GET_NUMBER = 0x08)
 *       w.writeIntLE(value)
 *       sendPacket(w.finish())
 *
 *   // Player submitted text:
 *   function on_text_submitted(input):
 *       encoded = input.encodeUTF8()
 *       w = PacketWriter(opcode=0xA0)
 *       w.writeByte(ACTION_OK = 0x01)
 *       w.writeByte(DIALOGUE_GET_TEXT = 0x09)
 *       w.writeShortLE(encoded.length)
 *       w.writeBytes(encoded)
 *       sendPacket(w.finish())
 * </pre>
 */
public final class NpcDialogueResponseHandler extends AbstractMaplePacketHandler {

    /** Response action: player cancelled or closed the dialogue window. */
    private static final byte ACTION_CANCEL = 0x00;
    /** Response action: player confirmed, pressed Next, or selected Yes/Accept. */
    private static final byte ACTION_OK     = 0x01;
    /** Response action: player pressed Prev or selected No/Decline. */
    private static final byte ACTION_PREV   = 0x02;

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - Received NPC_TALK_MORE/NPC_DIALOGUE_RESPONSE packet (0x3C/0xA0) from player: " + c.getPlayer().getName());
        
        final byte action       = slea.readByte();
        final byte dialogueCode = slea.readByte();
        
        System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - Action: " + action + ", DialogueCode: " + dialogueCode);

        final DialogueType dtype = DialogueType.fromCode(dialogueCode);

        // --- Determine the integer selection value ----------------------
        int selection = -1;

        if (action != ACTION_CANCEL && dtype != null) {
            switch (dtype) {
                case SELECTION:
                    // u8 selected_index
                    if (slea.available() >= 1) {
                        selection = slea.readByte() & 0xFF;
                    }
                    break;

                case GET_NUMBER:
                    // s32 LE value
                    if (slea.available() >= 4) {
                        selection = slea.readInt();
                    }
                    break;

                case GET_TEXT:
                    // u16 LE byte_len + UTF-8 bytes
                    if (slea.available() >= 2) {
                        final String input = slea.readMapleAsciiString();
                        if (c.getQM() != null) {
                            c.getQM().setGetText(input);
                        } else if (c.getCM() != null) {
                            c.getCM().setGetText(input);
                        }
                    }
                    // selection stays -1; scripts read via cm.getText()
                    break;

                default:
                    // OK / NEXT / PREV / YES_NO / ACCEPT_DECLINE
                    // No additional payload — selection remains -1.
                    break;
            }
        }

        handleResponse(c, action, dialogueCode, selection);
    }

    public void handleFlatBufferResponse(NpcDialogueResponse response, MapleClient c) {
        if (response == null) {
            return;
        }

        final byte action = (byte) response.action();
        final byte dialogueCode = (byte) response.dialogueType();
        final DialogueType dtype = DialogueType.fromCode(dialogueCode);
        int selection = response.selection();

        if (action != ACTION_CANCEL && dtype == DialogueType.GET_TEXT) {
            final String input = response.text();
            if (c.getQM() != null) {
                c.getQM().setGetText(input == null ? "" : input);
            } else if (c.getCM() != null) {
                c.getCM().setGetText(input == null ? "" : input);
            }
            selection = -1;
        }

        handleResponse(c, action, dialogueCode, selection);
    }

    private void handleResponse(MapleClient c, byte action, byte dialogueCode, int selection) {
        // --- Dispatch ---------------------------------------------------
        if (action == ACTION_CANCEL) {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - Player cancelled dialogue, disposing managers");
            // Player closed the window.
            // Clear NPC session if present
            if (c.getPlayer().getNpcSession() != null) {
                c.getPlayer().setNpcSession(null);
            }
            
            if (c.getQM() != null) {
                c.getQM().dispose();
            } else if (c.getCM() != null) {
                c.getCM().dispose();
            }
            return;
        }

        // Check for NPC interaction session first
        NpcSession session = c.getPlayer().getNpcSession();
        if (session != null && session.hasPendingOptions()) {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - NPC interaction session active, delegating to handleInteractionSelection(), selection: " + selection);
            handleInteractionSelection(c, session, selection, action);
            return;
        }

        if (c.getQM() != null) {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - Quest Manager active, delegating to QuestScriptManager, selection: " + selection);
            if (c.getQM().isStart()) {
                QuestScriptManager.getInstance().start(c, action, dialogueCode, selection);
            } else {
                QuestScriptManager.getInstance().end(c, action, dialogueCode, selection);
            }
        } else if (c.getCM() != null) {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - Conversation Manager active, delegating to NPCScriptManager.action(), selection: " + selection);
            NPCScriptManager.getInstance().action(c, action, dialogueCode, selection);
        } else {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handlePacket() - No active manager (QM or CM), ignoring response");
        }
    }

    /**
     * Handle selection from NPC interaction menu.
     */
    private void handleInteractionSelection(MapleClient c, NpcSession session, int selection, byte action) {
        System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Processing selection " + selection + " from NPC interaction menu");
        List<NpcInteractionOption> options = session.getPendingOptions();
        
        // Validate selection
        if (selection < 0 || selection >= options.size() || action != ACTION_OK) {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Invalid selection or action, aborting (selection: " + selection + ", options: " + options.size() + ", action: " + action + ")");
            c.getPlayer().setNpcSession(null);
            c.announce(MaplePacketCreator.enableActions());
            return;
        }

        // Get the selected option
        NpcInteractionOption selectedOption = options.get(selection);
        String questInfo = selectedOption.hasQuestId() ? " (questId=" + selectedOption.getQuestId() + ")" : "";
        System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Selected option: " + selectedOption.getLabel() + " (type: " + selectedOption.getType() + ")" + questInfo);
        
        // Get the NPC object
        MapleMapObject obj = c.getPlayer().getMap().getMapObject(session.getObjectId());
        if (!(obj instanceof MapleNPC)) {
            // NPC no longer exists
            System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - NPC object no longer exists (OID: " + session.getObjectId() + "), aborting");
            c.getPlayer().setNpcSession(null);
            c.announce(MaplePacketCreator.enableActions());
            return;
        }

        MapleNPC npc = (MapleNPC) obj;
        NpcInteractionContext context = new NpcInteractionContext(c, npc);

        // Clear the session before execution
        c.getPlayer().setNpcSession(null);

        // Log what action will be taken based on the interaction type
        switch (selectedOption.getType()) {
            case QUEST_AVAILABLE:
                System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Routing to QuestScriptManager.start() for quest " + selectedOption.getQuestId());
                break;
            case QUEST_COMPLETE:
                System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Routing to QuestScriptManager.end() for quest " + selectedOption.getQuestId());
                break;
            case QUEST_IN_PROGRESS:
                System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Routing to QuestScriptManager.start() for quest " + selectedOption.getQuestId());
                break;
            default:
                System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Executing " + selectedOption.getType() + " interaction");
                break;
        }

        // Execute the selected interaction (lambda will invoke QuestScriptManager or other handler)
        try {
            selectedOption.execute(context);
            System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Interaction executed successfully");
        } catch (Exception e) {
            System.out.println("[TRACE] NpcDialogueResponseHandler.handleInteractionSelection() - Exception during interaction execution: " + e.getMessage());
            e.printStackTrace();
            c.announce(MaplePacketCreator.enableActions());
        }
    }
}
