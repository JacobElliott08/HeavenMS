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
 * Wire-coded colour token for NPC dialogue selections.
 *
 * <p>Each constant represents exactly one byte in the binary
 * {@link ReplyBuilder} element stream.  Client-side rendering maps
 * the code to its actual RGBA value — the server never emits colour
 * markup strings.</p>
 *
 * <h2>Binary layout in an ELEM_SELECTION entry</h2>
 * <pre>
 *   [u8 ELEM_SELECTION = 0x03]
 *   [u8 index]
 *   [u8 color_code]   ← NpcColor.code written here
 *   [s32 LE item_id]
 *   [u16 LE str_len]
 *   [u8[]  UTF-8 label]
 * </pre>
 *
 * <h2>Wire-code table</h2>
 * <pre>
 *   0x00  DEFAULT  — inherits the client's default text colour
 *   0x01  RED
 *   0x02  BLUE
 *   0x03  GREEN
 *   0x04  PURPLE
 *   0x05  ORANGE
 *   0x06  GOLD
 * </pre>
 */
public enum NpcColor {

    DEFAULT((byte) 0x00),
    RED    ((byte) 0x01),
    BLUE   ((byte) 0x02),
    GREEN  ((byte) 0x03),
    PURPLE ((byte) 0x04),
    ORANGE ((byte) 0x05),
    GOLD   ((byte) 0x06);

    // -------------------------------------------------------------------------

    /** Single-byte wire value written into the element stream. */
    public final byte code;

    NpcColor(byte code) {
        this.code = code;
    }

    // -------------------------------------------------------------------------
    // Reverse lookup — O(1) via direct-mapped array
    // -------------------------------------------------------------------------

    private static final NpcColor[] BY_CODE = new NpcColor[7];

    static {
        for (NpcColor c : values()) {
            BY_CODE[c.code & 0xFF] = c;
        }
    }

    /**
     * Returns the {@link NpcColor} for the given wire byte, or
     * {@link #DEFAULT} if the code is unrecognised.
     *
     * @param code the byte read from the element stream
     * @return the matching constant
     */
    public static NpcColor fromCode(byte code) {
        int idx = code & 0xFF;
        if (idx < BY_CODE.length && BY_CODE[idx] != null) {
            return BY_CODE[idx];
        }
        return DEFAULT;
    }
}
