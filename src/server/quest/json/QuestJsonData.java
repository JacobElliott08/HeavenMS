package server.quest.json;

import java.util.List;
import java.util.Map;

/**
 * JSON data model for quest information
 */
public class QuestJsonData {
    private int questId;
    private QuestInfo info;
    private QuestRequirements requirements;
    private QuestActions actions;
    
    public static class QuestInfo {
        private String name;
        private String parent;
        private Map<String, String> descriptions; // notStarted, inProgress, completed
        private Integer area;
        private Integer order;
        private Integer timeLimit;
        private Integer timeLimit2;
        private Boolean autoStart;
        private Boolean autoPreComplete;
        private Boolean autoComplete;
        private Integer medalItemId;
        
        // Getters
        public String getName() { return name; }
        public String getParent() { return parent; }
        public Map<String, String> getDescriptions() { return descriptions; }
        public Integer getArea() { return area; }
        public Integer getOrder() { return order; }
        public Integer getTimeLimit() { return timeLimit; }
        public Integer getTimeLimit2() { return timeLimit2; }
        public Boolean getAutoStart() { return autoStart; }
        public Boolean getAutoPreComplete() { return autoPreComplete; }
        public Boolean getAutoComplete() { return autoComplete; }
        public Integer getMedalItemId() { return medalItemId; }
    }
    
    public static class QuestRequirements {
        private RequirementSet start;
        private RequirementSet complete;
        
        public RequirementSet getStart() { return start; }
        public RequirementSet getComplete() { return complete; }
    }
    
    public static class RequirementSet {
        private Integer npc;
        private Integer minLevel;
        private Integer maxLevel;
        private List<Integer> jobs;
        private List<ItemRequirement> items;
        private List<MonsterRequirement> monsters;
        private List<PrerequisiteQuest> prerequisiteQuests;
        private List<PrerequisiteQuest> completedQuests;
        private Integer meso;
        private Integer repeatIntervalMinutes;
        private String startDate;
        private String endDate;
        private String fieldEnter;
        private String startScript;
        private String endScript;
        private Integer minPetTameness;
        private Integer minMonsterBookCards;
        private Boolean normalAutoStart;
        private Integer infoNumber;
        private List<String> infoEx;
        private List<Integer> requiredBuffs;
        private List<Integer> forbiddenBuffs;
        private String dayByDay;
        
        // Getters
        public Integer getNpc() { return npc; }
        public Integer getMinLevel() { return minLevel; }
        public Integer getMaxLevel() { return maxLevel; }
        public List<Integer> getJobs() { return jobs; }
        public List<ItemRequirement> getItems() { return items; }
        public List<MonsterRequirement> getMonsters() { return monsters; }
        public List<PrerequisiteQuest> getPrerequisiteQuests() { return prerequisiteQuests; }
        public List<PrerequisiteQuest> getCompletedQuests() { return completedQuests; }
        public Integer getMeso() { return meso; }
        public Integer getRepeatIntervalMinutes() { return repeatIntervalMinutes; }
        public String getStartDate() { return startDate; }
        public String getEndDate() { return endDate; }
        public String getFieldEnter() { return fieldEnter; }
        public String getStartScript() { return startScript; }
        public String getEndScript() { return endScript; }
        public Integer getMinPetTameness() { return minPetTameness; }
        public Integer getMinMonsterBookCards() { return minMonsterBookCards; }
        public Boolean getNormalAutoStart() { return normalAutoStart; }
        public Integer getInfoNumber() { return infoNumber; }
        public List<String> getInfoEx() { return infoEx; }
        public List<Integer> getRequiredBuffs() { return requiredBuffs; }
        public List<Integer> getForbiddenBuffs() { return forbiddenBuffs; }
        public String getDayByDay() { return dayByDay; }
    }
    
    public static class QuestActions {
        private ActionSet onStart;
        private ActionSet onComplete;
        
        public ActionSet getOnStart() { return onStart; }
        public ActionSet getOnComplete() { return onComplete; }
    }
    
    public static class ActionSet {
        private Integer experience;
        private Integer meso;
        private List<ItemReward> items;
        private List<SkillReward> skills;
        private Integer nextQuest;
        private Integer fame;
        private Integer buffItemId;
        private Integer petSkill;
        private Integer petTameness;
        private Integer petSpeed;
        private String info;
        private Integer minLevel;
        private Integer npc;
        private Integer intervalMinutes;
        
        // Getters
        public Integer getExperience() { return experience; }
        public Integer getMeso() { return meso; }
        public List<ItemReward> getItems() { return items; }
        public List<SkillReward> getSkills() { return skills; }
        public Integer getNextQuest() { return nextQuest; }
        public Integer getFame() { return fame; }
        public Integer getBuffItemId() { return buffItemId; }
        public Integer getPetSkill() { return petSkill; }
        public Integer getPetTameness() { return petTameness; }
        public Integer getPetSpeed() { return petSpeed; }
        public String getInfo() { return info; }
        public Integer getMinLevel() { return minLevel; }
        public Integer getNpc() { return npc; }
        public Integer getIntervalMinutes() { return intervalMinutes; }
    }
    
    public static class ItemRequirement {
        private int itemId;
        private Integer count;
        
        public int getItemId() { return itemId; }
        public Integer getCount() { return count != null ? count : 1; }
    }
    
    public static class ItemReward {
        private int itemId;
        private Integer count;
        private Integer jobRestriction;
        private Integer probability;
        private Integer gender;
        private String expirationDate;
        private Integer potentialGrade;
        
        public int getItemId() { return itemId; }
        public Integer getCount() { return count != null ? count : 1; }
        public Integer getJobRestriction() { return jobRestriction; }
        public Integer getProbability() { return probability; }
        public Integer getGender() { return gender; }
        public String getExpirationDate() { return expirationDate; }
        public Integer getPotentialGrade() { return potentialGrade; }
    }
    
    public static class MonsterRequirement {
        private int monsterId;
        private Integer count;
        
        public int getMonsterId() { return monsterId; }
        public Integer getCount() { return count != null ? count : 1; }
    }
    
    public static class PrerequisiteQuest {
        private int questId;
        private String state; // NOT_STARTED, STARTED, COMPLETED
        
        public int getQuestId() { return questId; }
        public String getState() { return state; }
        public int getStateValue() {
            if (state == null) return -1;
            return switch (state) {
                case "NOT_STARTED" -> 0;
                case "STARTED" -> 1;
                case "COMPLETED" -> 2;
                default -> -1;
            };
        }
    }
    
    public static class SkillReward {
        private int skillId;
        private Integer skillLevel;
        private Integer masterLevel;
        
        public int getSkillId() { return skillId; }
        public Integer getSkillLevel() { return skillLevel; }
        public Integer getMasterLevel() { return masterLevel; }
    }
    
    // Main getters
    public int getQuestId() { return questId; }
    public QuestInfo getInfo() { return info; }
    public QuestRequirements getRequirements() { return requirements; }
    public QuestActions getActions() { return actions; }
}
