package server.quest.json;

import com.google.gson.Gson;
import provider.MapleData;
import provider.MapleDataDirectoryEntry;
import provider.MapleDataFileEntry;
import server.quest.*;
import server.quest.actions.*;
import server.quest.requirements.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads quest data from JSON files instead of XML WZ files.
 * Provides a cleaner, more readable quest data format.
 */
public class JsonQuestLoader {
    
    private final String jsonQuestPath;
    private final Gson gson;
    
    public JsonQuestLoader(String wzPath) {
        this.jsonQuestPath = wzPath + "/Quest.wz/json/";
        this.gson = new Gson();
    }
    
    /**
     * Load a quest from JSON file
     */
    public void loadQuestFromJson(MapleQuest quest, int questId) throws IOException {
        // Find the JSON file (grouped by thousands)
        int rangeStart = (questId / 1000) * 1000;
        String jsonFile = jsonQuestPath + rangeStart + "/" + questId + ".json";
        
        File file = new File(jsonFile);
        if (!file.exists()) {
            throw new IOException("Quest JSON file not found: " + jsonFile);
        }
        
        // Parse JSON
        QuestJsonData questData;
        try (FileReader reader = new FileReader(file)) {
            questData = gson.fromJson(reader, QuestJsonData.class);
        }
        
        if (questData == null) {
            throw new IOException("Failed to parse quest JSON: " + jsonFile);
        }
        
        // Populate quest object
        populateQuestFromJson(quest, questData);
    }
    
    /**
     * Populate a MapleQuest object from JSON data
     */
    private void populateQuestFromJson(MapleQuest quest, QuestJsonData data) {
        // Set basic info
        if (data.getInfo() != null) {
            QuestJsonData.QuestInfo info = data.getInfo();
            
            if (info.getName() != null) {
                quest.setName(info.getName());
            }
            if (info.getParent() != null) {
                quest.setParent(info.getParent());
            }
            if (info.getTimeLimit() != null) {
                quest.setTimeLimit(info.getTimeLimit());
            }
            if (info.getTimeLimit2() != null) {
                quest.setTimeLimit2(info.getTimeLimit2());
            }
            if (info.getAutoStart() != null && info.getAutoStart()) {
                quest.setAutoStart(true);
            }
            if (info.getAutoPreComplete() != null && info.getAutoPreComplete()) {
                quest.setAutoPreComplete(true);
            }
            if (info.getAutoComplete() != null && info.getAutoComplete()) {
                quest.setAutoComplete(true);
            }
            if (info.getMedalItemId() != null) {
                quest.addMedal(info.getMedalItemId());
            }
        }
        
        // Load requirements
        if (data.getRequirements() != null) {
            QuestJsonData.QuestRequirements requirements = data.getRequirements();
            
            if (requirements.getStart() != null) {
                loadRequirements(quest, requirements.getStart(), true);
            }
            if (requirements.getComplete() != null) {
                loadRequirements(quest, requirements.getComplete(), false);
            }
        }
        
        // Load actions
        if (data.getActions() != null) {
            QuestJsonData.QuestActions actions = data.getActions();
            
            if (actions.getOnStart() != null) {
                loadActions(quest, actions.getOnStart(), true);
            }
            if (actions.getOnComplete() != null) {
                loadActions(quest, actions.getOnComplete(), false);
            }
        }
    }
    
    /**
     * Load requirements from JSON into quest
     */
    private void loadRequirements(MapleQuest quest, QuestJsonData.RequirementSet reqSet, boolean isStart) {
        // NPC requirement
        if (reqSet.getNpc() != null) {
            quest.addRequirement(MapleQuestRequirementType.NPC, 
                new NpcRequirement(quest, reqSet.getNpc()), isStart);
        }
        
        // Level requirements
        if (reqSet.getMinLevel() != null) {
            quest.addRequirement(MapleQuestRequirementType.MIN_LEVEL,
                new MinLevelRequirement(quest, reqSet.getMinLevel()), isStart);
        }
        if (reqSet.getMaxLevel() != null) {
            quest.addRequirement(MapleQuestRequirementType.MAX_LEVEL,
                new MaxLevelRequirement(quest, reqSet.getMaxLevel()), isStart);
        }
        
        // Job requirement
        if (reqSet.getJobs() != null && !reqSet.getJobs().isEmpty()) {
            quest.addRequirement(MapleQuestRequirementType.JOB,
                new JobRequirement(quest, reqSet.getJobs()), isStart);
        }
        
        // Item requirements
        if (reqSet.getItems() != null && !reqSet.getItems().isEmpty()) {
            List<ItemRequirement.ItemData> items = new ArrayList<>();
            for (QuestJsonData.ItemRequirement item : reqSet.getItems()) {
                items.add(new ItemRequirement.ItemData(item.getItemId(), item.getCount()));
            }
            quest.addRequirement(MapleQuestRequirementType.ITEM,
                new ItemRequirement(quest, items), isStart);
        }
        
        // Monster requirements
        if (reqSet.getMonsters() != null && !reqSet.getMonsters().isEmpty()) {
            List<MobRequirement.MobData> mobs = new ArrayList<>();
            for (QuestJsonData.MonsterRequirement mob : reqSet.getMonsters()) {
                mobs.add(new MobRequirement.MobData(mob.getMonsterId(), mob.getCount()));
                quest.addRelevantMob(mob.getMonsterId());
            }
            quest.addRequirement(MapleQuestRequirementType.MOB,
                new MobRequirement(quest, mobs), isStart);
        }
        
        // Quest prerequisites
        if (reqSet.getPrerequisiteQuests() != null && !reqSet.getPrerequisiteQuests().isEmpty()) {
            List<QuestRequirement.QuestData> quests = new ArrayList<>();
            for (QuestJsonData.PrerequisiteQuest pq : reqSet.getPrerequisiteQuests()) {
                quests.add(new QuestRequirement.QuestData(pq.getQuestId(), pq.getStateValue()));
            }
            quest.addRequirement(MapleQuestRequirementType.QUEST,
                new QuestRequirement(quest, quests), isStart);
        }
        
        // Completed quests requirement
        if (reqSet.getCompletedQuests() != null && !reqSet.getCompletedQuests().isEmpty()) {
            List<Integer> completedQuests = new ArrayList<>();
            for (QuestJsonData.PrerequisiteQuest cq : reqSet.getCompletedQuests()) {
                completedQuests.add(cq.getQuestId());
            }
            quest.addRequirement(MapleQuestRequirementType.COMPLETED_QUEST,
                new CompletedQuestRequirement(quest, completedQuests), isStart);
        }
        
        // Meso requirement
        if (reqSet.getMeso() != null) {
            quest.addRequirement(MapleQuestRequirementType.MESO,
                new MesoRequirement(quest, reqSet.getMeso()), isStart);
        }
        
        // Interval (repeat) requirement
        if (reqSet.getRepeatIntervalMinutes() != null) {
            quest.addRequirement(MapleQuestRequirementType.INTERVAL,
                new IntervalRequirement(quest, reqSet.getRepeatIntervalMinutes()), isStart);
            quest.setRepeatable(true);
        }
        
        // Date requirements
        if (reqSet.getEndDate() != null) {
            quest.addRequirement(MapleQuestRequirementType.END_DATE,
                new EndDateRequirement(quest, reqSet.getEndDate()), isStart);
        }
        
        // Script requirements
        if (reqSet.getStartScript() != null || reqSet.getEndScript() != null) {
            String script = isStart ? reqSet.getStartScript() : reqSet.getEndScript();
            if (script != null) {
                quest.addRequirement(MapleQuestRequirementType.SCRIPT,
                    new ScriptRequirement(quest, script), isStart);
            }
        }
        
        // Pet requirements
        if (reqSet.getMinPetTameness() != null) {
            quest.addRequirement(MapleQuestRequirementType.MIN_PET_TAMENESS,
                new MinTamenessRequirement(quest, reqSet.getMinPetTameness()), isStart);
        }
        
        // Monster book requirement
        if (reqSet.getMinMonsterBookCards() != null) {
            quest.addRequirement(MapleQuestRequirementType.MONSTER_BOOK,
                new MonsterBookRequirement(quest, reqSet.getMinMonsterBookCards()), isStart);
        }
        
        // Info requirements
        if (reqSet.getInfoNumber() != null) {
            quest.addRequirement(MapleQuestRequirementType.INFO_NUMBER,
                new InfoNumberRequirement(quest, reqSet.getInfoNumber().shortValue()), isStart);
        }
        
        if (reqSet.getInfoEx() != null && !reqSet.getInfoEx().isEmpty()) {
            quest.addRequirement(MapleQuestRequirementType.INFO_EX,
                new InfoExRequirement(quest, reqSet.getInfoEx()), isStart);
        }
        
        // Buff requirements
        if (reqSet.getRequiredBuffs() != null && !reqSet.getRequiredBuffs().isEmpty()) {
            quest.addRequirement(MapleQuestRequirementType.BUFF,
                new BuffRequirement(quest, reqSet.getRequiredBuffs()), isStart);
        }
        
        if (reqSet.getForbiddenBuffs() != null && !reqSet.getForbiddenBuffs().isEmpty()) {
            quest.addRequirement(MapleQuestRequirementType.EXCEPT_BUFF,
                new ExceptBuffRequirement(quest, reqSet.getForbiddenBuffs()), isStart);
        }
    }
    
    /**
     * Load actions from JSON into quest
     */
    private void loadActions(MapleQuest quest, QuestJsonData.ActionSet actSet, boolean isStart) {
        // Experience reward
        if (actSet.getExperience() != null) {
            quest.addAction(MapleQuestActionType.EXP,
                new ExpAction(quest, actSet.getExperience()), isStart);
        }
        
        // Meso reward
        if (actSet.getMeso() != null) {
            quest.addAction(MapleQuestActionType.MESO,
                new MesoAction(quest, actSet.getMeso()), isStart);
        }
        
        // Item rewards
        if (actSet.getItems() != null && !actSet.getItems().isEmpty()) {
            List<ItemAction.ItemData> items = new ArrayList<>();
            for (QuestJsonData.ItemReward item : actSet.getItems()) {
                ItemAction.ItemData itemData = new ItemAction.ItemData(
                    item.getItemId(), 
                    item.getCount(),
                    item.getJobRestriction(),
                    item.getGender(),
                    item.getProbability()
                );
                items.add(itemData);
            }
            quest.addAction(MapleQuestActionType.ITEM,
                new ItemAction(quest, items), isStart);
        }
        
        // Skill rewards
        if (actSet.getSkills() != null && !actSet.getSkills().isEmpty()) {
            List<SkillAction.SkillData> skills = new ArrayList<>();
            for (QuestJsonData.SkillReward skill : actSet.getSkills()) {
                SkillAction.SkillData skillData = new SkillAction.SkillData(
                    skill.getSkillId(),
                    skill.getSkillLevel() != null ? skill.getSkillLevel() : 0,
                    skill.getMasterLevel() != null ? skill.getMasterLevel() : 0
                );
                skills.add(skillData);
            }
            quest.addAction(MapleQuestActionType.SKILL,
                new SkillAction(quest, skills), isStart);
        }
        
        // Next quest
        if (actSet.getNextQuest() != null) {
            quest.addAction(MapleQuestActionType.NEXTQUEST,
                new NextQuestAction(quest, actSet.getNextQuest()), isStart);
        }
        
        // Fame reward
        if (actSet.getFame() != null) {
            quest.addAction(MapleQuestActionType.FAME,
                new FameAction(quest, actSet.getFame()), isStart);
        }
        
        // Buff action
        if (actSet.getBuffItemId() != null) {
            quest.addAction(MapleQuestActionType.BUFF,
                new BuffAction(quest, actSet.getBuffItemId()), isStart);
        }
        
        // Pet actions
        if (actSet.getPetSkill() != null) {
            quest.addAction(MapleQuestActionType.PETSKILL,
                new PetSkillAction(quest, actSet.getPetSkill()), isStart);
        }
        if (actSet.getPetTameness() != null) {
            quest.addAction(MapleQuestActionType.PETTAMENESS,
                new PetTamenessAction(quest, actSet.getPetTameness()), isStart);
        }
        if (actSet.getPetSpeed() != null) {
            quest.addAction(MapleQuestActionType.PETSPEED,
                new PetSpeedAction(quest, actSet.getPetSpeed()), isStart);
        }
    }
    
    /**
     * Check if JSON quest file exists for the given quest ID
     */
    public boolean jsonQuestExists(int questId) {
        int rangeStart = (questId / 1000) * 1000;
        String jsonFile = jsonQuestPath + rangeStart + "/" + questId + ".json";
        return new File(jsonFile).exists();
    }
}
