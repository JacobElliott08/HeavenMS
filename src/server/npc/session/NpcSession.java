package server.npc.session;

import server.npc.interaction.NpcInteractionOption;
import java.util.List;

/**
 * Tracks the current NPC interaction state for a player to prevent:
 * - Double NPC interactions
 * - Script race conditions
 * - Multiple concurrent NPC states
 */
public class NpcSession {

    private final int npcId;
    private final int objectId;
    private List<NpcInteractionOption> pendingOptions;
    private final long startTime;

    public NpcSession(int npcId, int objectId) {
        this.npcId = npcId;
        this.objectId = objectId;
        this.startTime = System.currentTimeMillis();
    }

    public int getNpcId() {
        return npcId;
    }

    public int getObjectId() {
        return objectId;
    }

    public List<NpcInteractionOption> getPendingOptions() {
        return pendingOptions;
    }

    public void setPendingOptions(List<NpcInteractionOption> options) {
        this.pendingOptions = options;
    }

    public boolean hasPendingOptions() {
        return pendingOptions != null && !pendingOptions.isEmpty();
    }

    public long getStartTime() {
        return startTime;
    }

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - startTime > timeoutMillis;
    }
}