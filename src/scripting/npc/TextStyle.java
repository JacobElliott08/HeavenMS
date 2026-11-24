package scripting.npc;

import protocol.generated.maple.npc.TextColor;
import protocol.generated.maple.npc.TextEffect;

/**
 * Explicit style for {@link ReplyBuilder#text(String, TextStyle...)}.
 *
 * <p>Colors and effects are serialized through the typed
 * {@code Text.color} / {@code Text.effects} fields for every NPC reply.</p>
 */
public enum TextStyle {
    DEFAULT,
    RED,
    BLUE,
    GREEN,

    BOLD,
    ITALIC,
    UNDERLINE;

    /** Typed {@link TextColor} value, or -1 when this style is not a color. */
    int typedColor() {
        switch (this) {
            case DEFAULT: return TextColor.Default;
            case RED: return TextColor.Red;
            case BLUE: return TextColor.Blue;
            case GREEN: return TextColor.Green;
            default: return -1;
        }
    }

    /** Typed {@link TextEffect} value, or -1 when this style is not an effect. */
    int typedEffect() {
        switch (this) {
            case BOLD: return TextEffect.Bold;
            case ITALIC: return TextEffect.Italic;
            case UNDERLINE: return TextEffect.Underline;
            default: return -1;
        }
    }
}

