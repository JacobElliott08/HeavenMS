package scripting.npc;

public enum TextStyle {
    DEFAULT(0x00, 0x00),
    RED(0x01, 0x01),
    BLUE(0x01, 0x02),
    GREEN(0x01, 0x03),

    BOLD(0x02, 0x00),
    ITALIC(0x03, 0x00),
    UNDERLINE(0x04, 0x00);

    final byte type;
    final byte value;

    TextStyle(int type, int value) {
        this.type = (byte) type;
        this.value = (byte) value;
    }
}


