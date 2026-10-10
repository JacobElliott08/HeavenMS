package server.npc.interaction;

public enum NpcInteractionType {

    SHOP,
    QUEST_AVAILABLE,     // Quest can be started (yellow !)
    QUEST_COMPLETE,      // Quest can be completed (yellow ?)
    QUEST_IN_PROGRESS,   // Quest in progress but not completable yet (blue ?)
    SCRIPT,
    CHAT,
    STORAGE,
    CUSTOM

}