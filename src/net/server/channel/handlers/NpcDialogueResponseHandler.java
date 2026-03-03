/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.server.channel.handlers;

import client.MapleClient;
import net.AbstractMaplePacketHandler;
import scripting.npc.DialogueType;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestScriptManager;
import tools.data.input.SeekableLittleEndianAccessor;

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
        final byte action       = slea.readByte();
        final byte dialogueCode = slea.readByte();

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

        // --- Dispatch ---------------------------------------------------
        if (action == ACTION_CANCEL) {
            // Player closed the window.
            if (c.getQM() != null) {
                c.getQM().dispose();
            } else if (c.getCM() != null) {
                c.getCM().dispose();
            }
            return;
        }

        if (c.getQM() != null) {
            if (c.getQM().isStart()) {
                QuestScriptManager.getInstance().start(c, action, dialogueCode, selection);
            } else {
                QuestScriptManager.getInstance().end(c, action, dialogueCode, selection);
            }
        } else if (c.getCM() != null) {
            NPCScriptManager.getInstance().action(c, action, dialogueCode, selection);
        }
    }
}
