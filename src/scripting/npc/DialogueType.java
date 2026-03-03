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
package scripting.npc;

/**
 * Identifies the interaction type of an NPC_DIALOGUE packet.
 *
 * <h2>Binary role</h2>
 * <p>The {@link #code} byte is written as the {@code dialogue_type} field
 * immediately after {@code npc_id} in the server→client
 * {@code NPC_DIALOGUE} packet, and is echoed back in the client→server
 * {@code NPC_DIALOGUE_RESPONSE} packet so the server can validate state
 * without tracking it separately.</p>
 *
 * <h2>Server→Client packet layout context</h2>
 * <pre>
 * [u16 LE  opcode         = NPC_DIALOGUE (0x200)]
 * [u8      speaker_type]
 * [s32 LE  npc_id]
 * [u8      dialogue_type]   ← this enum's code
 * [u8      element_count]
 * [u8[]    elements...]
 * -- optional trailing fields depending on dialogue_type --
 * GET_NUMBER: [s32 LE def][s32 LE min][s32 LE max]
 * GET_TEXT:   [u16 LE len][u8[] UTF-8 default text]
 * </pre>
 *
 * <h2>Client→Server response wire byte</h2>
 * <pre>
 * action byte:
 *   0x00 = cancel / close / no / decline
 *   0x01 = ok / next / yes / accept
 *   0x02 = prev
 * </pre>
 */
public enum DialogueType {

    /**
     * Single dismiss button — "Ok".
     * Response carries only {@code action = 0x01}.
     */
    OK             ((byte) 0x01),

    /**
     * "Next ›" button only.
     * Response carries {@code action = 0x01} (next) or {@code 0x00} (close).
     */
    NEXT           ((byte) 0x02),

    /**
     * "‹ Prev" button only.
     * Response carries {@code action = 0x02} (prev) or {@code 0x00} (close).
     */
    PREV           ((byte) 0x03),

    /**
     * Both "‹ Prev" and "Next ›" buttons.
     * Response carries {@code action = 0x01} (next) or {@code 0x02} (prev).
     */
    NEXT_PREV      ((byte) 0x04),

    /**
     * Yes / No prompt.
     * Response: {@code action = 0x01} yes, {@code action = 0x00} no.
     */
    YES_NO         ((byte) 0x05),

    /**
     * Accept / Decline prompt.
     * Response: {@code action = 0x01} accept, {@code action = 0x00} decline.
     */
    ACCEPT_DECLINE ((byte) 0x06),

    /**
     * Clickable selection list built from {@code ELEM_SELECTION} elements.
     * Response carries {@code action = 0x01} + {@code [u8 selected_index]}.
     * {@code action = 0x00} means the player closed the window.
     */
    SELECTION      ((byte) 0x07),

    /**
     * Numeric input field.
     * Trailing packet fields: {@code [s32 LE default][s32 LE min][s32 LE max]}.
     * Response carries {@code action = 0x01} + {@code [s32 LE value]}, or
     * {@code action = 0x00} for cancel.
     */
    GET_NUMBER     ((byte) 0x08),

    /**
     * Free-text input field.
     * Trailing packet field: {@code [u16 LE len][u8[] UTF-8 default text]}.
     * Response carries {@code action = 0x01} + {@code [u16 LE len][u8[] UTF-8 input]},
     * or {@code action = 0x00} for cancel.
     */
    GET_TEXT       ((byte) 0x09);

    // -------------------------------------------------------------------------

    /** Wire byte written into the packet {@code dialogue_type} field. */
    public final byte code;

    DialogueType(byte code) {
        this.code = code;
    }

    // -------------------------------------------------------------------------
    // Reverse lookup — used by NpcDialogueResponseHandler
    // -------------------------------------------------------------------------

    private static final DialogueType[] BY_CODE = new DialogueType[10];

    static {
        for (DialogueType t : values()) {
            BY_CODE[t.code & 0xFF] = t;
        }
    }

    /**
     * Resolves the wire byte back to the enum constant.
     *
     * @param code the byte read from the response packet
     * @return the matching type, or {@code null} if unknown
     */
    public static DialogueType fromCode(byte code) {
        int idx = code & 0xFF;
        if (idx < BY_CODE.length) {
            return BY_CODE[idx];
        }
        return null;
    }
}
