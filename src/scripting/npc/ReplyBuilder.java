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

import client.MapleClient;
import net.opcodes.SendOpcode;
import tools.data.output.MaplePacketLittleEndianWriter;

import java.nio.charset.StandardCharsets;

/**
 * Data-oriented, zero-legacy NPC dialogue packet builder.
 *
 * =========================================================================
 * PROTOCOL OVERVIEW — NPC_DIALOGUE (opcode 0x200)
 * =========================================================================
 *
 * SERVER → CLIENT  —  NPC_DIALOGUE packet
 * ─────────────────────────────────────────────────────────────────────────
 *  Offset  Size    Field
 *  ──────  ──────  ──────────────────────────────────────────────────────
 *    0     u16 LE  opcode          = 0x200 (NPC_DIALOGUE)
 *    2     u8      speaker_type    0x00=NPC-left  0x01=NPC-right  0x02=player
 *    3     s32 LE  npc_id
 *    7     u8      dialogue_type   see DialogueType enum
 *    8     u8      element_count   number of elements that follow
 *    9     …       elements        see element format below
 *    …     …       [trailing fields — present only for specific types]
 *                  GET_NUMBER:  [s32 LE default][s32 LE min][s32 LE max]
 *                  GET_TEXT:    [u16 LE byte_len][u8[] UTF-8 default]
 *
 * ELEMENT FORMAT
 * ─────────────────────────────────────────────────────────────────────────
 *  ELEM_TEXT       0x01  [u16 LE byte_len][u8[] UTF-8]
 *  ELEM_LINEBREAK  0x02  (no payload — standalone newline)
 *  ELEM_SELECTION  0x03  [u8 index][u8 color_code][s32 LE item_id][u16 LE byte_len][u8[] UTF-8 label]
 *  ELEM_IMAGE_REF  0x04  [u8 ref_type: 0=item 1=mob 2=skill][s32 LE ref_id]
 *  ELEM_CUSTOM_TAG 0x05  [s32 LE tag_id][u16 LE byte_len][u8[] UTF-8 payload]
 *
 *   item_id = -1  →  no item reference
 *
 * CLIENT → SERVER  —  NPC_DIALOGUE_RESPONSE packet (opcode 0xA0)
 * ─────────────────────────────────────────────────────────────────────────
 *  Offset  Size    Field
 *  ──────  ──────  ──────────────────────────────────────────────────────
 *    0     u16 LE  opcode          = 0xA0 (NPC_DIALOGUE_RESPONSE)
 *    2     u8      action          0x00=cancel  0x01=ok/next/yes  0x02=prev/no
 *    3     u8      dialogue_type   echoed from server packet
 *    -- conditional payload (only when action != 0x00): --
 *    SELECTION:   [u8  selected_index]
 *    GET_NUMBER:  [s32 LE value]
 *    GET_TEXT:    [u16 LE byte_len][u8[] UTF-8 input]
 *
 * CLIENT-SIDE PSEUDO-CODE
 * ─────────────────────────────────────────────────────────────────────────
 *  function handle_NPC_DIALOGUE(reader):
 *      speaker_type  = reader.readByte()
 *      npc_id        = reader.readInt()
 *      dialogue_type = reader.readByte()
 *      element_count = reader.readByte()
 *
 *      ui = NpcDialogueUI.create(npc_id, speaker_type, dialogue_type)
 *
 *      for i in 0..element_count:
 *          opcode = reader.readByte()
 *          switch opcode:
 *              case ELEM_TEXT (0x01):
 *                  len  = reader.readShortLE()
 *                  text = reader.readBytes(len).decodeUTF8()
 *                  ui.appendText(text)
 *
 *              case ELEM_LINEBREAK (0x02):
 *                  ui.appendLinebreak()
 *
 *              case ELEM_SELECTION (0x03):
 *                  index      = reader.readByte()
 *                  color_code = reader.readByte()
 *                  item_id    = reader.readIntLE()
 *                  len        = reader.readShortLE()
 *                  label      = reader.readBytes(len).decodeUTF8()
 *                  color      = Color.fromCode(color_code)
 *                  ui.addSelection(index, label, color, item_id)
 *
 *              case ELEM_IMAGE_REF (0x04):
 *                  ref_type = reader.readByte()
 *                  ref_id   = reader.readIntLE()
 *                  ui.addImageRef(ref_type, ref_id)
 *
 *              case ELEM_CUSTOM_TAG (0x05):
 *                  tag_id  = reader.readIntLE()
 *                  len     = reader.readShortLE()
 *                  payload = reader.readBytes(len).decodeUTF8()
 *                  ui.addCustomTag(tag_id, payload)
 *
 *      switch dialogue_type:
 *          case GET_NUMBER (0x08):
 *              def = reader.readIntLE()
 *              min = reader.readIntLE()
 *              max = reader.readIntLE()
 *              ui.setNumberInput(def, min, max)
 *          case GET_TEXT (0x09):
 *              len      = reader.readShortLE()
 *              def_text = reader.readBytes(len).decodeUTF8()
 *              ui.setTextInput(def_text)
 *
 *      ui.show()
 *
 * RESPONSE RENDERING LOOP
 * ─────────────────────────────────────────────────────────────────────────
 *  function render_NpcDialogueUI(ui):
 *      for elem in ui.elements:
 *          switch elem.type:
 *              TEXT:       drawText(elem.text, defaultColor)
 *              LINEBREAK:  advanceLine()
 *              SELECTION:  drawClickable(elem.index, elem.label,
 *                                        elem.color, elem.item_id)
 *              IMAGE_REF:  drawImage(lookupImage(elem.ref_type, elem.ref_id))
 *              CUSTOM_TAG: dispatchCustomRenderer(elem.tag_id, elem.payload)
 *
 *  function on_selection_clicked(index):
 *      writer = PacketWriter(opcode=NPC_DIALOGUE_RESPONSE)
 *      writer.writeByte(ACTION_OK)
 *      writer.writeByte(DIALOGUE_SELECTION)
 *      writer.writeByte(index)
 *      sendPacket(writer.finish())
 *
 * =========================================================================
 * INTERNAL DESIGN NOTES
 * =========================================================================
 *
 * ZERO ALLOCATION ON THE HOT PATH
 *   Instances are pooled per-thread via ThreadLocal.  acquire() returns the
 *   same object every time for a given NPC-handler thread, reusing the
 *   pre-allocated element buffer.  A send*() call dispatches the packet and
 *   leaves the instance ready for the next acquire().
 *
 * FLAT ELEMENT BUFFER
 *   Elements are written directly into a byte[] (LE-encoded) as they are
 *   added — no List<Element>, no per-element heap object.  elementCount is a
 *   plain int field.  On send*() the buffer is copied once into a
 *   MaplePacketLittleEndianWriter and delivered to the client.
 *
 * BUFFER GROWTH
 *   Initial capacity: INITIAL_CAPACITY bytes.  Grows by doubling — same
 *   strategy as ArrayList.  Buffer is retained across resets; only pos and
 *   elementCount are zeroed.
 *
 * WHY THIS IS BETTER THAN STRING MARKUP
 *   • No fragile positional parsing of characters like #b, #r, #L0#
 *   • New element types add a byte to an enum, not a regex/parser change
 *   • Color, item IDs, and text are typed fields — no accidental int/color swap
 *   • Client renders structured data directly — zero string scanning
 *   • Buffer is reused; no per-interaction String concatenation chain
 *
 * TRADEOFFS vs OBJECT HIERARCHY
 *   • Cannot inspect individual elements after building (write-only)
 *   • No random-access modification — elements are append-only
 *   • Thread-local pool requires single-threaded build → send per dialogue
 *   These constraints hold for all practical NPC scripts.
 */
public final class ReplyBuilder {

    // =========================================================================
    // Element opcode constants
    // =========================================================================

    /** Plain UTF-8 text run. */
    static final byte ELEM_TEXT        = 0x01;
    /** Explicit linebreak (the client renders a newline). */
    static final byte ELEM_LINEBREAK   = 0x02;
    /** Clickable selection entry. */
    static final byte ELEM_SELECTION   = 0x03;
    /** Inline image reference (item/mob/skill icon). */
    static final byte ELEM_IMAGE_REF   = 0x04;
    /** Extensible custom tag. */
    static final byte ELEM_CUSTOM_TAG  = 0x05;

    // =========================================================================
    // Speaker type constants (written into the packet header)
    // =========================================================================

    public static final byte SPEAKER_NPC_LEFT  = 0x00;
    public static final byte SPEAKER_NPC_RIGHT = 0x01;
    public static final byte SPEAKER_PLAYER    = 0x02;

    // =========================================================================
    // Image reference type constants (for imageRef())
    // =========================================================================

    public static final byte REF_ITEM  = 0x00;
    public static final byte REF_MOB   = 0x01;
    public static final byte REF_SKILL = 0x02;

    // =========================================================================
    // Buffer
    // =========================================================================

    private static final int INITIAL_CAPACITY = 2048;

    // =========================================================================
    // Thread-local pool
    // =========================================================================

    private static final ThreadLocal<ReplyBuilder> POOL =
            ThreadLocal.withInitial(ReplyBuilder::new);

    // =========================================================================
    // Instance state — all primitives
    // =========================================================================

    /** Flat LE-encoded element byte stream. */
    private byte[] elemBuf;
    /** Write cursor into elemBuf. */
    private int    pos;
    /** Count of elements added so far. */
    private int    elementCount;

    /** Running selection index incremented by each writeSelectionHeader call. */
    private int    selectionIndex;

    /** Bound at acquire(). */
    private MapleClient client;
    private int         npcId;
    private byte        speakerType;

    // Extra fields for GET_NUMBER — populated by sendGetNumber()
    private int numDefault;
    private int numMin;
    private int numMax;

    // Extra field for GET_TEXT — populated by sendGetText()
    private String textDefault;

    // =========================================================================
    // Constructor (private — use pool)
    // =========================================================================

    private ReplyBuilder() {
        this.elemBuf = new byte[INITIAL_CAPACITY];
    }

    // =========================================================================
    // Acquisition — package-private, called only by NPCConversationManager
    // =========================================================================

    /**
     * Acquires the thread-local builder, binds it to the given NPC context,
     * and clears all element state from the previous use.
     * Default speaker is {@link #SPEAKER_NPC_LEFT}.
     *
     * @param client active client session
     * @param npcId  NPC template ID for the packet header
     * @return ready-to-use builder
     */
    static ReplyBuilder acquire(MapleClient client, int npcId) {
        return acquire(client, npcId, SPEAKER_NPC_LEFT);
    }

    /**
     * Same as {@link #acquire(MapleClient, int)} with an explicit speaker.
     *
     * @param speakerType one of {@link #SPEAKER_NPC_LEFT},
     *                    {@link #SPEAKER_NPC_RIGHT}, or {@link #SPEAKER_PLAYER}
     */
    static ReplyBuilder acquire(MapleClient client, int npcId, byte speakerType) {
        ReplyBuilder rb = POOL.get();
        rb.client      = client;
        rb.npcId       = npcId;
        rb.speakerType = speakerType;
        rb.pos            = 0;
        rb.elementCount   = 0;
        rb.selectionIndex = 0;
        rb.numDefault = 0;
        rb.numMin     = 0;
        rb.numMax     = 0;
        rb.textDefault = "";
        return rb;
    }

    // =========================================================================
    // Builder API — element append methods
    // =========================================================================

    /**
     * Appends a plain UTF-8 text run.
     *
     * <p>Multiple consecutive {@code text()} calls are each emitted as
     * separate ELEM_TEXT entries — the client concatenates them visually.
     * Use {@link #linebreak()} or embed {@code \n} in the string to control
     * layout (the client interprets {@code \n} in text runs as a soft wrap;
     * use {@link #linebreak()} for a hard paragraph break).</p>
     */
    public ReplyBuilder text(String s) {
        ensureCapacity(3 + s.length() * 3); // worst-case UTF-8 expansion
        elemBuf[pos++] = ELEM_TEXT;
        writeElemString(s);
        elementCount++;
        return this;
    }

    /**
     * Appends an explicit line break element.
     * The client advances to the next line regardless of the current text cursor.
     */
    public ReplyBuilder linebreak() {
        ensureCapacity(1);
        elemBuf[pos++] = ELEM_LINEBREAK;
        elementCount++;
        return this;
    }

    /**
     * Appends a plain selection entry with the default colour and no item.
     */
    public ReplyBuilder selection(String label) {
        writeSelectionHeader(NpcColor.DEFAULT, -1);
        writeElemString(label);
        elementCount++;
        return this;
    }

    /**
     * Appends a coloured selection entry.
     */
    public ReplyBuilder selection(String label, NpcColor color) {
        writeSelectionHeader(color, -1);
        writeElemString(label);
        elementCount++;
        return this;
    }

    /**
     * Appends a selection entry that references an item name/icon.
     * The client displays the item name inline before the label text.
     *
     * @param label  additional label text displayed after the item name
     * @param itemId item template ID ({@code -1} = none)
     */
    public ReplyBuilder selection(String label, int itemId) {
        writeSelectionHeader(NpcColor.DEFAULT, itemId);
        writeElemString(label);
        elementCount++;
        return this;
    }

    /**
     * Appends a selection entry with both a colour and an item reference.
     */
    public ReplyBuilder selection(String label, NpcColor color, int itemId) {
        writeSelectionHeader(color, itemId);
        writeElemString(label);
        elementCount++;
        return this;
    }

    /**
     * Appends an inline image reference (item icon, mob image, or skill icon).
     *
     * @param refType one of {@link #REF_ITEM}, {@link #REF_MOB}, {@link #REF_SKILL}
     * @param refId   the template / WZ ID for the image
     */
    public ReplyBuilder imageRef(byte refType, int refId) {
        ensureCapacity(6); // opcode(1) + refType(1) + refId(4)
        elemBuf[pos++] = ELEM_IMAGE_REF;
        elemBuf[pos++] = refType;
        writeElemIntLE(refId);
        elementCount++;
        return this;
    }

    /**
     * Appends a custom-tag element with a numeric ID and payload string.
     * The client dispatches custom rendering based on {@code tagId}.
     * tag IDs ≥ 0x1000 are reserved for server-defined extensions.
     */
    public ReplyBuilder customTag(int tagId, String payload) {
        ensureCapacity(7 + payload.length() * 3);
        elemBuf[pos++] = ELEM_CUSTOM_TAG;
        writeElemIntLE(tagId);
        writeElemString(payload);
        elementCount++;
        return this;
    }

    // =========================================================================
    // Terminal (send) methods
    // =========================================================================

    /**
     * Sends as a {@link DialogueType#SELECTION} packet (clicking a list entry).
     * This is the standard terminal method for menus.
     *
     * <p>Pool contract: do not use this builder reference after calling.</p>
     */
    public void send() {
        dispatch(DialogueType.SELECTION);
    }

    /**
     * Sends as a {@link DialogueType#OK} packet (single dismiss button).
     * Typical for short informational messages.
     */
    public void sendOk() {
        dispatch(DialogueType.OK);
    }

    /**
     * Sends with a "Next ›" button only ({@link DialogueType#NEXT}).
     */
    public void sendNext() {
        dispatch(DialogueType.NEXT);
    }

    /**
     * Sends with a "‹ Prev" button only ({@link DialogueType#PREV}).
     */
    public void sendPrev() {
        dispatch(DialogueType.PREV);
    }

    /**
     * Sends with both "‹ Prev" and "Next ›" buttons ({@link DialogueType#NEXT_PREV}).
     */
    public void sendNextPrev() {
        dispatch(DialogueType.NEXT_PREV);
    }

    /**
     * Sends a Yes / No prompt ({@link DialogueType#YES_NO}).
     */
    public void sendYesNo() {
        dispatch(DialogueType.YES_NO);
    }

    /**
     * Sends an Accept / Decline prompt ({@link DialogueType#ACCEPT_DECLINE}).
     */
    public void sendAcceptDecline() {
        dispatch(DialogueType.ACCEPT_DECLINE);
    }

    /**
     * Sends a numeric-input dialogue ({@link DialogueType#GET_NUMBER}).
     * Trailing packet fields appended after the element list:
     * {@code [s32 LE default][s32 LE min][s32 LE max]}.
     *
     * @param def initial value shown in the input field
     * @param min minimum accepted value
     * @param max maximum accepted value
     */
    public void sendGetNumber(int def, int min, int max) {
        this.numDefault = def;
        this.numMin     = min;
        this.numMax     = max;
        dispatch(DialogueType.GET_NUMBER);
    }

    /**
     * Sends a free-text-input dialogue ({@link DialogueType#GET_TEXT}).
     * Trailing field appended after the element list:
     * {@code [u16 LE len][u8[] UTF-8 default_text]}.
     *
     * @param defaultText the pre-filled text shown in the input box
     */
    public void sendGetText(String defaultText) {
        this.textDefault = (defaultText != null) ? defaultText : "";
        dispatch(DialogueType.GET_TEXT);
    }

    // =========================================================================
    // Internal: packet construction and dispatch
    // =========================================================================

    /**
     * Builds and sends the {@code NPC_DIALOGUE} packet for the given type.
     * State is cleared (cursor reset) so the instance is pool-safe after return.
     *
     * <h3>Hexadecimal packet layout example — DIALOGUE_SELECTION:</h3>
     * <pre>
     * Bytes  Value   Description
     * ─────  ──────  ──────────────────────────────────────────────
     * 00 02  0x0200  opcode NPC_DIALOGUE (LE)
     * 00             speaker_type = NPC_LEFT
     * 2B 09 00 00    npc_id = 2347 (LE)
     * 07             dialogue_type = SELECTION
     * 03             element_count = 3
     *
     * 01             ELEM_TEXT
     * 0E 00          str_len = 14 (LE)
     * 48 65 6C 6C    "Hell"
     * 6F 20 61 64    "o ad"
     * 76 65 6E 74    "vent"
     * 75 72 65 72    "urer"
     * 2E 21          ".!"   (14 bytes total)
     *
     * 03             ELEM_SELECTION
     * 00             index = 0
     * 00             color = DEFAULT
     * FF FF FF FF    item_id = -1 (no item, LE)
     * 05 00          str_len = 5 (LE)
     * 54 72 61 69    "Trai"
     * 6E             "n"
     *
     * 03             ELEM_SELECTION
     * 01             index = 1
     * 01             color = RED
     * 10 57 1E 00    item_id = 2020112 (LE)
     * 07 00          str_len = 7 (LE)
     * 42 75 79 20    "Buy "
     * 69 74 65 6D    "item"
     * </pre>
     */
    private void dispatch(DialogueType dtype) {
        // Pre-compute element buffer size.  8-byte header + element bytes
        // + optional trailing fields.
        int trailingSize = 0;
        byte[] textDefaultBytes = null;
        if (dtype == DialogueType.GET_NUMBER) {
            trailingSize = 12; // 3 × s32
        } else if (dtype == DialogueType.GET_TEXT) {
            textDefaultBytes = textDefault.getBytes(StandardCharsets.UTF_8);
            trailingSize = 2 + textDefaultBytes.length;
        }

        // 2 (opcode) + 1 (speaker) + 4 (npcId) + 1 (dialogueType) + 1 (elemCount)
        final int headerSize = 9;
        final MaplePacketLittleEndianWriter pw =
                new MaplePacketLittleEndianWriter(headerSize + pos + trailingSize);

        pw.writeShort(SendOpcode.NPC_DIALOGUE.getValue());
        pw.write(speakerType);
        pw.writeInt(npcId);
        pw.write(dtype.code);
        pw.write((byte) elementCount);

        // Write the element buffer (byte-by-byte — avoids an extra array copy)
        for (int i = 0; i < pos; i++) {
            pw.write(elemBuf[i]);
        }

        // Trailing fields
        if (dtype == DialogueType.GET_NUMBER) {
            pw.writeInt(numDefault);
            pw.writeInt(numMin);
            pw.writeInt(numMax);
        } else if (dtype == DialogueType.GET_TEXT && textDefaultBytes != null) {
            pw.writeShort(textDefaultBytes.length);
            pw.write(textDefaultBytes);
        }

        client.announce(pw.getPacket());
        // Instance is now safe for pool reuse — reset happens on next acquire().
    }

    // =========================================================================
    // Internal: element-stream write helpers (LE encoding)
    // =========================================================================

    /**
     * Writes the fixed-size portion of an ELEM_SELECTION entry.
     * The string (label) must be written immediately after by the caller.
     */
    private void writeSelectionHeader(NpcColor color, int itemId) {
        ensureCapacity(7); // opcode(1) + index(1) + color(1) + itemId(4)
        elemBuf[pos++] = ELEM_SELECTION;
        elemBuf[pos++] = (byte) (selectionIndex++ & 0xFF);
        elemBuf[pos++] = color.code;
        writeElemIntLE(itemId);
    }

    /**
     * Writes a length-prefixed UTF-8 string as
     * {@code [u16 LE byte_len][u8[] bytes]} into the element buffer.
     */
    private void writeElemString(String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        final int    len   = bytes.length;
        ensureCapacity(2 + len);
        elemBuf[pos++] = (byte)  (len        & 0xFF);   // low byte
        elemBuf[pos++] = (byte) ((len >>> 8)  & 0xFF);  // high byte (LE)
        System.arraycopy(bytes, 0, elemBuf, pos, len);
        pos += len;
    }

    /**
     * Writes a 4-byte little-endian int into the element buffer.
     */
    private void writeElemIntLE(int v) {
        ensureCapacity(4);
        elemBuf[pos++] = (byte)  (v         & 0xFF);
        elemBuf[pos++] = (byte) ((v >>>  8) & 0xFF);
        elemBuf[pos++] = (byte) ((v >>> 16) & 0xFF);
        elemBuf[pos++] = (byte) ((v >>> 24) & 0xFF);
    }

    // =========================================================================
    // Buffer growth — amortised O(1)
    // =========================================================================

    private void ensureCapacity(int needed) {
        if (pos + needed <= elemBuf.length) {
            return;
        }
        int newLen = Math.max(elemBuf.length * 2, pos + needed);
        byte[] grown = new byte[newLen];
        System.arraycopy(elemBuf, 0, grown, 0, pos);
        elemBuf = grown;
    }
}
