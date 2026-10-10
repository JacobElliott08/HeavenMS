package server.npc.interaction;

import java.util.function.Consumer;

public class NpcInteractionOption {

    private final NpcInteractionType type;
    private final String label;
    private final Consumer<NpcInteractionContext> handler;
    private final Integer questId;  // Optional quest metadata

    public NpcInteractionOption(
        NpcInteractionType type,
        String label,
        Consumer<NpcInteractionContext> handler
    ) {
        this(type, label, handler, null);
    }

    public NpcInteractionOption(
        NpcInteractionType type,
        String label,
        Consumer<NpcInteractionContext> handler,
        Integer questId
    ) {
        this.type = type;
        this.label = label;
        this.handler = handler;
        this.questId = questId;
    }

    public NpcInteractionType getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }
    
    public Integer getQuestId() {
        return questId;
    }
    
    public boolean hasQuestId() {
        return questId != null;
    }

    public void execute(NpcInteractionContext context) {
        handler.accept(context);
    }
}