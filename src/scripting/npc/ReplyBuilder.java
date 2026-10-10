package scripting.npc;

import client.MapleClient;
import com.google.flatbuffers.FlatBufferBuilder;
import protocol.generated.maple.npc.CustomTag;
import protocol.generated.maple.npc.DialogueElement;
import protocol.generated.maple.npc.ImageRef;
import protocol.generated.maple.npc.LineBreak;
import protocol.generated.maple.npc.NpcDialogue;
import protocol.generated.maple.npc.Quest;
import protocol.generated.maple.npc.Selection;
import protocol.generated.maple.npc.Text;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

import java.util.ArrayList;
import java.util.List;

/** Builds the FlatBuffers NPC dialogue response without legacy inline markup. */
public final class ReplyBuilder {
    public static final byte SPEAKER_NPC_LEFT = 0x00;
    public static final byte SPEAKER_NPC_RIGHT = 0x01;
    public static final byte SPEAKER_PLAYER = 0x02;
    public static final byte REF_ITEM = 0x00;
    public static final byte REF_MOB = 0x01;
    public static final byte REF_SKILL = 0x02;

    private static final ThreadLocal<ReplyBuilder> POOL = ThreadLocal.withInitial(ReplyBuilder::new);
    private final List<Element> elements = new ArrayList<>();
    private MapleClient client;
    private int npcId;
    private byte speakerType;
    private int numDefault;
    private int numMin;
    private int numMax;
    private String textDefault = "";
    private int selectionIndex;

    private ReplyBuilder() {
    }

    public static ReplyBuilder acquire(MapleClient client, int npcId) {
        return acquire(client, npcId, SPEAKER_NPC_LEFT);
    }

    public static ReplyBuilder acquire(MapleClient client, int npcId, byte speakerType) {
        ReplyBuilder reply = POOL.get();
        reply.client = client;
        reply.npcId = npcId;
        reply.speakerType = speakerType;
        reply.elements.clear();
        reply.selectionIndex = 0;
        reply.numDefault = 0;
        reply.numMin = 0;
        reply.numMax = 0;
        reply.textDefault = "";
        return reply;
    }

    public ReplyBuilder text(String value) {
        return text(value, new TextStyle[0]);
    }

    public ReplyBuilder text(String value, TextStyle... styles) {
        elements.add(Element.text(value, styles));
        return this;
    }

    public ReplyBuilder linebreak() {
        elements.add(Element.lineBreak());
        return this;
    }

    public ReplyBuilder selection(String label) {
        return selection(label, NpcColor.DEFAULT, -1);
    }

    public ReplyBuilder selection(String label, NpcColor color) {
        return selection(label, color, -1);
    }

    public ReplyBuilder selection(String label, int itemId) {
        return selection(label, NpcColor.DEFAULT, itemId);
    }

    public ReplyBuilder selection(String label, NpcColor color, int itemId) {
        elements.add(Element.selection(selectionIndex++, label, color.code, itemId));
        return this;
    }

    public ReplyBuilder questAvailable(int questId, String label) {
        return quest(questId, label, 1);
    }

    public ReplyBuilder questComplete(int questId, String label) {
        return quest(questId, label, 3);
    }

    public ReplyBuilder questInProgress(int questId, String label) {
        return quest(questId, label, 2);
    }

    private ReplyBuilder quest(int questId, String label, int state) {
        elements.add(Element.quest(selectionIndex++, questId, label, state));
        return this;
    }

    public ReplyBuilder questsAvailable(List<server.quest.MapleQuest> quests) {
        for (server.quest.MapleQuest quest : quests) questAvailable(quest.getId(), quest.getName());
        return this;
    }

    public ReplyBuilder questsComplete(List<server.quest.MapleQuest> quests) {
        for (server.quest.MapleQuest quest : quests) questComplete(quest.getId(), quest.getName());
        return this;
    }

    public ReplyBuilder questsInProgress(List<server.quest.MapleQuest> quests) {
        for (server.quest.MapleQuest quest : quests) questInProgress(quest.getId(), quest.getName());
        return this;
    }

    public ReplyBuilder imageRef(byte refType, int refId) {
        elements.add(Element.image(refType, refId));
        return this;
    }

    public ReplyBuilder customTag(int tagId, String payload) {
        elements.add(Element.customTag(tagId, payload));
        return this;
    }

    public void send() { dispatch(DialogueType.SELECTION); }
    public void sendOk() { dispatch(DialogueType.OK); }
    public void sendNext() { dispatch(DialogueType.NEXT); }
    public void sendPrev() { dispatch(DialogueType.PREV); }
    public void sendNextPrev() { dispatch(DialogueType.NEXT_PREV); }
    public void sendYesNo() { dispatch(DialogueType.YES_NO); }
    public void sendAcceptDecline() { dispatch(DialogueType.ACCEPT_DECLINE); }

    public void sendGetNumber(int def, int min, int max) {
        numDefault = def;
        numMin = min;
        numMax = max;
        dispatch(DialogueType.GET_NUMBER);
    }

    public void sendGetText(String defaultText) {
        textDefault = defaultText == null ? "" : defaultText;
        dispatch(DialogueType.GET_TEXT);
    }

    private void dispatch(DialogueType dialogueType) {
        FlatBufferBuilder builder = new FlatBufferBuilder(1024);
        int[] elementOffsets = new int[elements.size()];
        byte[] elementTypes = new byte[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            elementTypes[i] = element.type;
            elementOffsets[i] = element.create(builder);
        }

        int elementTypesOffset = NpcDialogue.createElementTypesVector(builder, elementTypes);
        int elementsOffset = NpcDialogue.createElementsVector(builder, elementOffsets);
        int textDefaultOffset = builder.createString(textDefault);
        NpcDialogue.startNpcDialogue(builder);
        NpcDialogue.addSpeakerType(builder, speakerType);
        NpcDialogue.addNpcId(builder, npcId);
        NpcDialogue.addDialogueType(builder, dialogueType.code);
        NpcDialogue.addElementTypes(builder, elementTypesOffset);
        NpcDialogue.addElements(builder, elementsOffset);
        NpcDialogue.addNumberDefault(builder, numDefault);
        NpcDialogue.addNumberMin(builder, numMin);
        NpcDialogue.addNumberMax(builder, numMax);
        NpcDialogue.addTextDefault(builder, textDefaultOffset);
        NpcDialogue.addSessionId(builder, npcId);
        NpcDialogue.addShowPrev(builder, dialogueType == DialogueType.PREV || dialogueType == DialogueType.NEXT_PREV);
        NpcDialogue.addShowNext(builder, dialogueType == DialogueType.NEXT || dialogueType == DialogueType.NEXT_PREV);
        int dialogue = NpcDialogue.endNpcDialogue(builder);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, dialogue);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_npc_NpcDialogue);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        System.out.println("[FlatBuffers->NpcDialogue] NpcId: " + npcId + ", Type: " + dialogueType + ", Elements: " + elements.size());
        client.announce(builder.sizedByteArray());
    }

    public static void sendEnd(MapleClient client, int npcId) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int end = protocol.generated.maple.npc.NpcDialogueEnd.createNpcDialogueEnd(builder, npcId, npcId);
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, end);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_npc_NpcDialogueEnd);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        System.out.println("[FlatBuffers->NpcDialogueEnd] NpcId: " + npcId);
        client.announce(builder.sizedByteArray());
    }

    private static final class Element {
        private final byte type;
        private final String text;
        private final TextStyle[] styles;
        private final int index;
        private final int color;
        private final int itemId;
        private final byte refType;
        private final int refId;
        private final int tagId;
        private final int questId;
        private final int questState;

        private Element(byte type, String text, TextStyle[] styles, int index, int color,
                        int itemId, byte refType, int refId, int tagId, int questId, int questState) {
            this.type = type;
            this.text = text == null ? "" : text;
            this.styles = styles == null ? new TextStyle[0] : styles;
            this.index = index;
            this.color = color;
            this.itemId = itemId;
            this.refType = refType;
            this.refId = refId;
            this.tagId = tagId;
            this.questId = questId;
            this.questState = questState;
        }

        static Element text(String value, TextStyle[] styles) {
            return new Element(DialogueElement.Text, value, styles, 0, 0, 0, (byte) 0, 0, 0, 0, 0);
        }

        static Element lineBreak() {
            return new Element(DialogueElement.LineBreak, "", null, 0, 0, 0, (byte) 0, 0, 0, 0, 0);
        }

        static Element selection(int index, String label, int color, int itemId) {
            return new Element(DialogueElement.Selection, label, null, index, color, itemId, (byte) 0, 0, 0, 0, 0);
        }

        static Element image(byte refType, int refId) {
            return new Element(DialogueElement.ImageRef, "", null, 0, 0, 0, refType, refId, 0, 0, 0);
        }

        static Element customTag(int tagId, String payload) {
            return new Element(DialogueElement.CustomTag, payload, null, 0, 0, 0, (byte) 0, 0, tagId, 0, 0);
        }

        static Element quest(int index, int questId, String label, int state) {
            return new Element(DialogueElement.Quest, label, null, index, 0, 0, (byte) 0, 0, 0, questId, state);
        }

        int create(FlatBufferBuilder builder) {
            switch (type) {
                case DialogueElement.Text:
                    int[] styleOffsets = new int[styles.length];
                    for (int i = 0; i < styles.length; i++) {
                        styleOffsets[i] = protocol.generated.maple.npc.TextStyle.createTextStyle(builder, styles[i].type, styles[i].value);
                    }
                    int stylesVector = Text.createStylesVector(builder, styleOffsets);
                    int value = builder.createString(text);
                    return Text.createText(builder, value, stylesVector);
                case DialogueElement.LineBreak:
                    LineBreak.startLineBreak(builder);
                    return LineBreak.endLineBreak(builder);
                case DialogueElement.Selection:
                    return Selection.createSelection(builder, index, color, itemId, builder.createString(text));
                case DialogueElement.ImageRef:
                    return ImageRef.createImageRef(builder, refType, refId);
                case DialogueElement.CustomTag:
                    return CustomTag.createCustomTag(builder, tagId, builder.createString(text));
                case DialogueElement.Quest:
                    return Quest.createQuest(builder, index, questId, questState, builder.createString(text));
                default:
                    throw new IllegalStateException("Unknown NPC dialogue element: " + type);
            }
        }
    }
}
