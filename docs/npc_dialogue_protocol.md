# NPC Dialogue Protocol

Binary network specification for the server-designed NPC dialogue system.  
All multi-byte integer fields are **little-endian** unless noted otherwise.  
All strings are **UTF-8** prefixed with a **u16 LE byte length**.

---

## Opcodes

| Direction        | Name                   | Opcode  |
|------------------|------------------------|---------|
| Server → Client  | `NPC_DIALOGUE`         | `0x200` |
| Client → Server  | `NPC_DIALOGUE_RESPONSE`| `0xA0`  |

> The legacy `NPC_TALK` opcode (`0x30`) is still used by `NPCTalkHandler` for
> the initial click-on-NPC trigger and is not part of this protocol.

---

## 1. Server → Client — `NPC_DIALOGUE` (`0x200`)

Sent by the server every time the NPC wants to display a new dialogue frame.

### 1.1 Packet Layout

```
Offset  Size      Field
──────  ────────  ─────────────────────────────────────────────────────────────
 0      u16 LE    opcode            = 0x200
 2      u8        speaker_type      see §1.2
 3      s32 LE    npc_id
 7      u8        dialogue_type     see §1.3
 8      u8        element_count     number of elements that follow
 9      …         elements          see §1.4 — repeated element_count times
 …      …         [trailing fields] see §1.5 — present only for certain types
```

### 1.2 Speaker Types

| Byte   | Meaning              |
|--------|----------------------|
| `0x00` | NPC portrait — left  |
| `0x01` | NPC portrait — right |
| `0x02` | Player portrait      |

### 1.3 Dialogue Types

The `dialogue_type` byte describes what UI controls to render.

| Byte   | Name             | Description                                          |
|--------|------------------|------------------------------------------------------|
| `0x01` | `OK`             | Single "Ok" dismiss button                           |
| `0x02` | `NEXT`           | "Next ›" button only                                 |
| `0x03` | `PREV`           | "‹ Prev" button only                                 |
| `0x04` | `NEXT_PREV`      | Both "‹ Prev" and "Next ›"                           |
| `0x05` | `YES_NO`         | "Yes" / "No" buttons                                 |
| `0x06` | `ACCEPT_DECLINE` | "Accept" / "Decline" buttons                         |
| `0x07` | `SELECTION`      | Clickable list built from `ELEM_SELECTION` elements  |
| `0x08` | `GET_NUMBER`     | Numeric input field (see §1.5 for trailing fields)   |
| `0x09` | `GET_TEXT`       | Free-text input field (see §1.5 for trailing fields) |

### 1.4 Element Stream

After the header, read exactly `element_count` elements sequentially.  
Each element starts with a 1-byte tag:

#### `ELEM_TEXT` — `0x01`
```
[u8 = 0x01]
[u16 LE  byte_len]
[u8[]    UTF-8 text]
```
Append the decoded string to the current display run.

#### `ELEM_LINEBREAK` — `0x02`
```
[u8 = 0x02]
```
No payload. Advance to the next line.

#### `ELEM_SELECTION` — `0x03`
```
[u8 = 0x03]
[u8      index]         — zero-based; echo back in the response
[u8      color_code]    — see Color Table below
[s32 LE  item_id]       — item icon to display; -1 = none
[u16 LE  byte_len]
[u8[]    UTF-8 label]
```

**Color Table**

| Byte   | Color   |
|--------|---------|
| `0x00` | DEFAULT — client's default text colour |
| `0x01` | RED     |
| `0x02` | BLUE    |
| `0x03` | GREEN   |
| `0x04` | PURPLE  |
| `0x05` | ORANGE  |
| `0x06` | GOLD    |

#### `ELEM_IMAGE_REF` — `0x04`
```
[u8 = 0x04]
[u8      ref_type]   — 0x00=item  0x01=mob  0x02=skill
[s32 LE  ref_id]     — game object ID to look up the icon
```
Render an inline icon using the appropriate asset atlas.

#### `ELEM_CUSTOM_TAG` — `0x05`
```
[u8 = 0x05]
[s32 LE  tag_id]
[u16 LE  byte_len]
[u8[]    UTF-8 payload]
```
Forward to a client-side custom renderer keyed by `tag_id`. Ignore unknown IDs.

### 1.5 Trailing Fields

Appended **after** all elements, only for the following types:

#### `GET_NUMBER` (`0x08`)
```
[s32 LE  default_value]
[s32 LE  min_value]
[s32 LE  max_value]
```

#### `GET_TEXT` (`0x09`)
```
[u16 LE  byte_len]
[u8[]    UTF-8 default_text]
```

No trailing fields for any other dialogue type.

---

## 2. Client → Server — `NPC_DIALOGUE_RESPONSE` (`0xA0`)

Sent by the client each time the player interacts with the dialogue window.

### 2.1 Packet Layout

```
Offset  Size      Field
──────  ────────  ─────────────────────────────────────────────────────────────
 0      u16 LE    opcode            = 0xA0
 2      u8        action            see §2.2
 3      u8        dialogue_type     echoed from the previous NPC_DIALOGUE packet
 4      …         [payload]         conditional — see §2.3
```

### 2.2 Action Byte

| Byte   | Meaning                              | When used                         |
|--------|--------------------------------------|-----------------------------------|
| `0x00` | Cancel / Close / No / Decline        | Player closed window; any type    |
| `0x01` | Ok / Next / Yes / Accept / Confirm   | Player advanced or confirmed      |
| `0x02` | Prev                                 | Player navigated back             |

When `action = 0x00` (cancel), **do not send any payload bytes** beyond `dialogue_type`.

### 2.3 Conditional Payload

Payload is **only present when `action != 0x00`**, and only for the types below.  
All other dialogue types (`OK`, `NEXT`, `PREV`, `NEXT_PREV`, `YES_NO`, `ACCEPT_DECLINE`) have no payload.

#### `SELECTION` (`0x07`)
```
[u8  selected_index]   — echoes the index from ELEM_SELECTION
```

#### `GET_NUMBER` (`0x08`)
```
[s32 LE  value]
```

#### `GET_TEXT` (`0x09`)
```
[u16 LE  byte_len]
[u8[]    UTF-8 input]
```

---

## 3. Full Parsing Examples (C++ pseudocode)

### 3.1 Receiving `NPC_DIALOGUE`

```cpp
void handle_NPC_DIALOGUE(PacketReader& r) {
    uint8_t  speaker_type  = r.readU8();
    int32_t  npc_id        = r.readS32LE();
    uint8_t  dialogue_type = r.readU8();
    uint8_t  element_count = r.readU8();

    NpcDialogueUI ui(npc_id, speaker_type, dialogue_type);

    for (int i = 0; i < element_count; ++i) {
        uint8_t tag = r.readU8();
        switch (tag) {
            case 0x01: { // ELEM_TEXT
                uint16_t len  = r.readU16LE();
                std::string s = r.readUtf8(len);
                ui.appendText(s);
                break;
            }
            case 0x02: { // ELEM_LINEBREAK
                ui.appendLinebreak();
                break;
            }
            case 0x03: { // ELEM_SELECTION
                uint8_t  index      = r.readU8();
                uint8_t  color_code = r.readU8();
                int32_t  item_id    = r.readS32LE();
                uint16_t len        = r.readU16LE();
                std::string label   = r.readUtf8(len);
                ui.addSelection(index, label, color_code, item_id);
                break;
            }
            case 0x04: { // ELEM_IMAGE_REF
                uint8_t ref_type = r.readU8();
                int32_t ref_id   = r.readS32LE();
                ui.addImageRef(ref_type, ref_id);
                break;
            }
            case 0x05: { // ELEM_CUSTOM_TAG
                int32_t  tag_id  = r.readS32LE();
                uint16_t len     = r.readU16LE();
                std::string payload = r.readUtf8(len);
                ui.addCustomTag(tag_id, payload);
                break;
            }
        }
    }

    // Trailing fields
    if (dialogue_type == 0x08) { // GET_NUMBER
        int32_t def_val = r.readS32LE();
        int32_t min_val = r.readS32LE();
        int32_t max_val = r.readS32LE();
        ui.setNumberInput(def_val, min_val, max_val);
    } else if (dialogue_type == 0x09) { // GET_TEXT
        uint16_t len       = r.readU16LE();
        std::string def_s  = r.readUtf8(len);
        ui.setTextInput(def_s);
    }

    ui.show();
}
```

### 3.2 Sending `NPC_DIALOGUE_RESPONSE`

```cpp
// Player clicked Next or Ok
void on_next_clicked(uint8_t dialogue_type) {
    PacketWriter w(0xA0);
    w.writeU8(0x01);          // ACTION_OK
    w.writeU8(dialogue_type); // echo back
    sendPacket(w.finish());
}

// Player clicked Prev
void on_prev_clicked(uint8_t dialogue_type) {
    PacketWriter w(0xA0);
    w.writeU8(0x02);          // ACTION_PREV
    w.writeU8(dialogue_type);
    sendPacket(w.finish());
}

// Player closed the window
void on_window_closed(uint8_t dialogue_type) {
    PacketWriter w(0xA0);
    w.writeU8(0x00);          // ACTION_CANCEL
    w.writeU8(dialogue_type);
    sendPacket(w.finish());   // no payload
}

// Player clicked a selection entry
void on_selection_clicked(uint8_t index, uint8_t dialogue_type) {
    PacketWriter w(0xA0);
    w.writeU8(0x01);          // ACTION_OK
    w.writeU8(dialogue_type); // = 0x07
    w.writeU8(index);
    sendPacket(w.finish());
}

// Player submitted a number
void on_number_submitted(int32_t value, uint8_t dialogue_type) {
    PacketWriter w(0xA0);
    w.writeU8(0x01);          // ACTION_OK
    w.writeU8(dialogue_type); // = 0x08
    w.writeS32LE(value);
    sendPacket(w.finish());
}

// Player submitted text
void on_text_submitted(const std::string& input, uint8_t dialogue_type) {
    std::vector<uint8_t> encoded = utf8Encode(input);
    PacketWriter w(0xA0);
    w.writeU8(0x01);                       // ACTION_OK
    w.writeU8(dialogue_type);              // = 0x09
    w.writeU16LE(encoded.size());
    w.writeBytes(encoded);
    sendPacket(w.finish());
}
```

---

## 4. Quick Reference

### Dialogue type → expected response action(s)

| Dialogue Type    | Byte   | Valid Action Bytes                        |
|------------------|--------|-------------------------------------------|
| OK               | `0x01` | `0x01` ok, `0x00` close                   |
| NEXT             | `0x02` | `0x01` next, `0x00` close                 |
| PREV             | `0x03` | `0x02` prev, `0x00` close                 |
| NEXT_PREV        | `0x04` | `0x01` next, `0x02` prev                  |
| YES_NO           | `0x05` | `0x01` yes, `0x00` no                     |
| ACCEPT_DECLINE   | `0x06` | `0x01` accept, `0x00` decline             |
| SELECTION        | `0x07` | `0x01` + `[u8 index]`, `0x00` close       |
| GET_NUMBER       | `0x08` | `0x01` + `[s32 LE value]`, `0x00` cancel  |
| GET_TEXT         | `0x09` | `0x01` + `[u16 len][utf8]`, `0x00` cancel |

### Element tag summary

| Tag    | Name           | Payload                                                  |
|--------|----------------|----------------------------------------------------------|
| `0x01` | TEXT           | `[u16 len][utf8]`                                        |
| `0x02` | LINEBREAK      | *(none)*                                                 |
| `0x03` | SELECTION      | `[u8 index][u8 color][s32 item_id][u16 len][utf8 label]` |
| `0x04` | IMAGE_REF      | `[u8 ref_type][s32 ref_id]`                              |
| `0x05` | CUSTOM_TAG     | `[s32 tag_id][u16 len][utf8 payload]`                    |
