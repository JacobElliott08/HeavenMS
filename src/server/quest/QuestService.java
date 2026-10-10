package server.quest;

import client.MapleCharacter;
import client.MapleQuestStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing quest data and NPC-quest relationships.
 * Provides efficient lookups and status determination for quest-related operations.
 */
public class QuestService {
    
    private static final QuestService instance = new QuestService();
    
    // Cache: NPC ID -> List of Quest IDs associated with that NPC
    private final Map<Integer, List<Integer>> npcToQuestsCache = new ConcurrentHashMap<>();
    
    // Flag to indicate if cache needs rebuilding
    private volatile boolean cacheInitialized = false;
    
    private QuestService() {
        // Private constructor for singleton
    }
    
    public static QuestService getInstance() {
        return instance;
    }
    
    /**
     * Initialize the NPC-to-quest cache by scanning all quests
     */
    public void initializeCache() {
        if (cacheInitialized) {
            return;
        }
        
        synchronized (this) {
            if (cacheInitialized) {
                return;
            }
            
            npcToQuestsCache.clear();
            
            // Iterate through all quests and build the cache
            for (MapleQuest quest : MapleQuest.getAllInstances()) {
                int questId = quest.getId();
                
                // Get start NPC
                int startNpc = quest.getNpcRequirement(false);
                if (startNpc != -1) {
                    npcToQuestsCache.computeIfAbsent(startNpc, k -> new ArrayList<>()).add(questId);
                }
                
                // Get complete NPC
                int completeNpc = quest.getNpcRequirement(true);
                if (completeNpc != -1 && completeNpc != startNpc) {
                    npcToQuestsCache.computeIfAbsent(completeNpc, k -> new ArrayList<>()).add(questId);
                }
            }
            
            cacheInitialized = true;
            System.out.println("[QuestService] Initialized NPC-to-quest cache with " + 
                npcToQuestsCache.size() + " NPCs");
        }
    }
    
    /**
     * Get all quests associated with a specific NPC
     */
    public List<Integer> getQuestsForNpc(int npcId) {
        if (!cacheInitialized) {
            initializeCache();
        }
        return npcToQuestsCache.getOrDefault(npcId, Collections.emptyList());
    }
    
    /**
     * Get the quest status for a specific NPC from the player's perspective
     * Priority: CAN_COMPLETE > CAN_START > IN_PROGRESS > UNAVAILABLE
     */
    public NpcQuestStatus getNpcQuestStatus(MapleCharacter chr, int npcId) {
        List<Integer> questIds = getQuestsForNpc(npcId);
        if (questIds.isEmpty()) {
            return NpcQuestStatus.NONE;
        }
        
        boolean hasCompletable = false;
        boolean hasStartable = false;
        boolean hasInProgress = false;
        
        for (int questId : questIds) {
            MapleQuest quest = MapleQuest.getInstance(questId);
            
            // Check if quest can be completed
            if (quest.canComplete(chr, npcId)) {
                hasCompletable = true;
                break; // Highest priority, return immediately
            }
            
            // Check if quest can be started
            if (quest.canStart(chr, npcId)) {
                hasStartable = true;
            }
            
            // Check if quest is in progress
            MapleQuestStatus status = chr.getQuest(quest);
            if (status.getStatus() == MapleQuestStatus.Status.STARTED) {
                hasInProgress = true;
            }
        }
        
        // Return status based on priority
        if (hasCompletable) {
            return NpcQuestStatus.CAN_COMPLETE;
        } else if (hasStartable) {
            return NpcQuestStatus.CAN_START;
        } else if (hasInProgress) {
            return NpcQuestStatus.IN_PROGRESS;
        } else {
            return NpcQuestStatus.UNAVAILABLE;
        }
    }
    
    /**
     * Get detailed quest information for an NPC
     */
    public List<QuestInfo> getDetailedQuestInfo(MapleCharacter chr, int npcId) {
        List<Integer> questIds = getQuestsForNpc(npcId);
        List<QuestInfo> results = new ArrayList<>();
        
        for (int questId : questIds) {
            MapleQuest quest = MapleQuest.getInstance(questId);
            MapleQuestStatus status = chr.getQuest(quest);
            
            QuestAvailability availability;
            if (quest.canComplete(chr, npcId)) {
                availability = QuestAvailability.CAN_COMPLETE;
            } else if (quest.canStart(chr, npcId)) {
                availability = QuestAvailability.CAN_START;
            } else if (status.getStatus() == MapleQuestStatus.Status.STARTED) {
                availability = QuestAvailability.IN_PROGRESS;
            } else if (status.getStatus() == MapleQuestStatus.Status.COMPLETED) {
                availability = QuestAvailability.COMPLETED;
            } else {
                availability = QuestAvailability.NOT_AVAILABLE;
            }
            
            results.add(new QuestInfo(questId, status.getStatus(), availability));
        }
        
        return results;
    }
    
    /**
     * Get quests that can be started at this NPC
     */
    public List<MapleQuest> getAvailableQuestsToStart(MapleCharacter chr, int npcId) {
        List<Integer> questIds = getQuestsForNpc(npcId);
        List<MapleQuest> available = new ArrayList<>();
        
        for (int questId : questIds) {
            MapleQuest quest = MapleQuest.getInstance(questId);
            if (quest.canStart(chr, npcId)) {
                available.add(quest);
            }
        }
        
        return available;
    }
    
    /**
     * Get quests that can be completed at this NPC
     */
    public List<MapleQuest> getAvailableQuestsToComplete(MapleCharacter chr, int npcId) {
        List<Integer> questIds = getQuestsForNpc(npcId);
        List<MapleQuest> completable = new ArrayList<>();
        
        for (int questId : questIds) {
            MapleQuest quest = MapleQuest.getInstance(questId);
            if (quest.canComplete(chr, npcId)) {
                completable.add(quest);
            }
        }
        
        return completable;
    }
    
    /**
     * Get quests that are in progress but cannot yet be completed at this NPC
     */
    public List<MapleQuest> getAvailableQuestsInProgress(MapleCharacter chr, int npcId) {
        List<Integer> questIds = getQuestsForNpc(npcId);
        List<MapleQuest> inProgress = new ArrayList<>();
        
        for (int questId : questIds) {
            MapleQuest quest = MapleQuest.getInstance(questId);
            MapleQuestStatus status = chr.getQuest(quest);
            
            // Only include if started but cannot be completed yet
            if (status.getStatus() == MapleQuestStatus.Status.STARTED && 
                !quest.canComplete(chr, npcId)) {
                inProgress.add(quest);
            }
        }
        
        return inProgress;
    }
    
    /**
     * Clear the cache (useful for reloading)
     */
    public void clearCache() {
        npcToQuestsCache.clear();
        cacheInitialized = false;
    }
    
    /**
     * Enum representing the overall quest status for an NPC
     */
    public enum NpcQuestStatus {
        NONE(0),           // No quests for this NPC
        UNAVAILABLE(1),    // Has quests but none are available
        IN_PROGRESS(2),    // Has quests in progress
        CAN_START(3),      // Can start a quest
        CAN_COMPLETE(4);   // Can complete a quest (highest priority)
        
        private final int value;
        
        NpcQuestStatus(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Enum representing quest availability for individual quests
     */
    public enum QuestAvailability {
        NOT_AVAILABLE,
        CAN_START,
        IN_PROGRESS,
        CAN_COMPLETE,
        COMPLETED
    }
    
    /**
     * Data class holding quest information
     */
    public static class QuestInfo {
        private final int questId;
        private final MapleQuestStatus.Status status;
        private final QuestAvailability availability;
        
        public QuestInfo(int questId, MapleQuestStatus.Status status, QuestAvailability availability) {
            this.questId = questId;
            this.status = status;
            this.availability = availability;
        }
        
        public int getQuestId() {
            return questId;
        }
        
        public MapleQuestStatus.Status getStatus() {
            return status;
        }
        
        public QuestAvailability getAvailability() {
            return availability;
        }
    }
}
