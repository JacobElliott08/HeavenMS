package tools.QuestXmlToJsonConverter;

import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Converts Quest WZ XML files to structured JSON format with meaningful property names.
 * Processes QuestInfo.img, Check.img, and Act.img files.
 */
public class QuestXmlToJsonConverter {
    
    private static final String WZ_PATH = System.getProperty("wzpath", "./wz");
    private static final String OUTPUT_PATH = WZ_PATH + "/Quest.wz/json/";
    
    private MapleDataProvider questData;
    private MapleData questInfo;
    private MapleData questCheck;
    private MapleData questAct;
    private Gson gson;
    
    public QuestXmlToJsonConverter() {
        questData = MapleDataProviderFactory.getDataProvider(new File(WZ_PATH + "/Quest.wz"));
        questInfo = questData.getData("QuestInfo.img");
        questCheck = questData.getData("Check.img");
        questAct = questData.getData("Act.img");
        
        gson = new GsonBuilder().setPrettyPrinting().create();
    }
    
    public static void main(String[] args) {
        System.out.println("Starting Quest XML to JSON conversion...");
        QuestXmlToJsonConverter converter = new QuestXmlToJsonConverter();
        converter.convertAllQuests();
        System.out.println("Conversion complete!");
    }
    
    public void convertAllQuests() {
        // Create output directory
        new File(OUTPUT_PATH).mkdirs();
        
        // Get all quest IDs from Check.img (it contains all quests)
        List<Integer> questIds = new ArrayList<>();
        for (MapleData child : questCheck.getChildren()) {
            try {
                int questId = Integer.parseInt(child.getName());
                questIds.add(questId);
            } catch (NumberFormatException e) {
                // Skip non-numeric entries
            }
        }
        
        Collections.sort(questIds);
        
        // Convert each quest
        int converted = 0;
        int errors = 0;
        for (int questId : questIds) {
            try {
                Map<String, Object> questJson = convertQuest(questId);
                if (questJson != null) {
                    saveQuestJson(questId, questJson);
                    converted++;
                    if (converted % 100 == 0) {
                        System.out.println("Converted " + converted + " quests...");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error converting quest " + questId + ": " + e.getMessage());
                errors++;
            }
        }
        
        System.out.println("\nConversion Summary:");
        System.out.println("  Successfully converted: " + converted);
        System.out.println("  Errors: " + errors);
        System.out.println("  Output directory: " + OUTPUT_PATH);
    }
    
    private Map<String, Object> convertQuest(int questId) {
        Map<String, Object> quest = new LinkedHashMap<>();
        quest.put("questId", questId);
        
        // Add quest info (metadata)
        Map<String, Object> info = convertQuestInfo(questId);
        if (info != null && !info.isEmpty()) {
            quest.put("info", info);
        }
        
        // Add requirements
        Map<String, Object> requirements = convertRequirements(questId);
        if (requirements != null && !requirements.isEmpty()) {
            quest.put("requirements", requirements);
        }
        
        // Add actions
        Map<String, Object> actions = convertActions(questId);
        if (actions != null && !actions.isEmpty()) {
            quest.put("actions", actions);
        }
        
        return quest;
    }
    
    private Map<String, Object> convertQuestInfo(int questId) {
        Map<String, Object> info = new LinkedHashMap<>();
        
        if (questInfo == null) return info;
        
        MapleData infoData = questInfo.getChildByPath(String.valueOf(questId));
        if (infoData == null) return info;
        
        // Basic info
        String name = MapleDataTool.getString("name", infoData, null);
        if (name != null) info.put("name", name);
        
        String parent = MapleDataTool.getString("parent", infoData, null);
        if (parent != null) info.put("parent", parent);
        
        // Descriptions (0=not started, 1=in progress, 2=completed)
        Map<String, String> descriptions = new LinkedHashMap<>();
        String desc0 = MapleDataTool.getString("0", infoData, null);
        if (desc0 != null) descriptions.put("notStarted", desc0);
        
        String desc1 = MapleDataTool.getString("1", infoData, null);
        if (desc1 != null) descriptions.put("inProgress", desc1);
        
        String desc2 = MapleDataTool.getString("2", infoData, null);
        if (desc2 != null) descriptions.put("completed", desc2);
        
        if (!descriptions.isEmpty()) {
            info.put("descriptions", descriptions);
        }
        
        // Numeric properties
        int area = MapleDataTool.getInt("area", infoData, -1);
        if (area != -1) info.put("area", area);
        
        int order = MapleDataTool.getInt("order", infoData, -1);
        if (order != -1) info.put("order", order);
        
        int timeLimit = MapleDataTool.getInt("timeLimit", infoData, 0);
        if (timeLimit > 0) info.put("timeLimit", timeLimit);
        
        int timeLimit2 = MapleDataTool.getInt("timeLimit2", infoData, 0);
        if (timeLimit2 > 0) info.put("timeLimit2", timeLimit2);
        
        // Boolean flags
        int autoStart = MapleDataTool.getInt("autoStart", infoData, 0);
        if (autoStart == 1) info.put("autoStart", true);
        
        int autoPreComplete = MapleDataTool.getInt("autoPreComplete", infoData, 0);
        if (autoPreComplete == 1) info.put("autoPreComplete", true);
        
        int autoComplete = MapleDataTool.getInt("autoComplete", infoData, 0);
        if (autoComplete == 1) info.put("autoComplete", true);
        
        int medalId = MapleDataTool.getInt("viewMedalItem", infoData, 0);
        if (medalId != 0) info.put("medalItemId", medalId);
        
        return info;
    }
    
    private Map<String, Object> convertRequirements(int questId) {
        Map<String, Object> requirements = new LinkedHashMap<>();
        
        MapleData checkData = questCheck.getChildByPath(String.valueOf(questId));
        if (checkData == null) return requirements;
        
        // Start requirements (0)
        MapleData startReqData = checkData.getChildByPath("0");
        if (startReqData != null) {
            Map<String, Object> startReqs = convertRequirementSet(startReqData);
            if (!startReqs.isEmpty()) {
                requirements.put("start", startReqs);
            }
        }
        
        // Complete requirements (1)
        MapleData completeReqData = checkData.getChildByPath("1");
        if (completeReqData != null) {
            Map<String, Object> completeReqs = convertRequirementSet(completeReqData);
            if (!completeReqs.isEmpty()) {
                requirements.put("complete", completeReqs);
            }
        }
        
        return requirements;
    }
    
    private Map<String, Object> convertRequirementSet(MapleData reqData) {
        Map<String, Object> reqs = new LinkedHashMap<>();
        
        for (MapleData child : reqData.getChildren()) {
            String name = child.getName();
            
            switch (name) {
                case "npc":
                    reqs.put("npc", MapleDataTool.getInt(child));
                    break;
                case "lvmin":
                    reqs.put("minLevel", MapleDataTool.getInt(child));
                    break;
                case "lvmax":
                    reqs.put("maxLevel", MapleDataTool.getInt(child));
                    break;
                case "job":
                    reqs.put("jobs", convertJobList(child));
                    break;
                case "item":
                    reqs.put("items", convertItemList(child));
                    break;
                case "mob":
                    reqs.put("monsters", convertMobList(child));
                    break;
                case "quest":
                    reqs.put("prerequisiteQuests", convertQuestList(child));
                    break;
                case "questComplete":
                    reqs.put("completedQuests", convertQuestList(child));
                    break;
                case "money":
                    reqs.put("meso", MapleDataTool.getInt(child));
                    break;
                case "interval":
                    reqs.put("repeatIntervalMinutes", MapleDataTool.getInt(child));
                    break;
                case "start":
                    reqs.put("startDate", MapleDataTool.getString(child));
                    break;
                case "end":
                    reqs.put("endDate", MapleDataTool.getString(child));
                    break;
                case "fieldEnter":
                    reqs.put("fieldEnter", MapleDataTool.getString(child));
                    break;
                case "startscript":
                    reqs.put("startScript", MapleDataTool.getString(child));
                    break;
                case "endscript":
                    reqs.put("endScript", MapleDataTool.getString(child));
                    break;
                case "pet":
                    reqs.put("petRequirement", convertPetRequirement(child));
                    break;
                case "pettamenessmin":
                    reqs.put("minPetTameness", MapleDataTool.getInt(child));
                    break;
                case "mbmin":
                    reqs.put("minMonsterBookCards", MapleDataTool.getInt(child));
                    break;
                case "normalAutoStart":
                    if (MapleDataTool.getInt(child) == 1) {
                        reqs.put("normalAutoStart", true);
                    }
                    break;
                case "infoNumber":
                    reqs.put("infoNumber", MapleDataTool.getInt(child));
                    break;
                case "infoex":
                    reqs.put("infoEx", convertInfoExList(child));
                    break;
                case "buff":
                    reqs.put("requiredBuffs", convertBuffList(child));
                    break;
                case "exceptbuff":
                    reqs.put("forbiddenBuffs", convertBuffList(child));
                    break;
                case "daybyday":
                    reqs.put("dayByDay", MapleDataTool.getString(child));
                    break;
            }
        }
        
        return reqs;
    }
    
    private Map<String, Object> convertActions(int questId) {
        Map<String, Object> actions = new LinkedHashMap<>();
        
        MapleData actData = questAct.getChildByPath(String.valueOf(questId));
        if (actData == null) return actions;
        
        // Start actions (0)
        MapleData startActData = actData.getChildByPath("0");
        if (startActData != null) {
            Map<String, Object> startActs = convertActionSet(startActData);
            if (!startActs.isEmpty()) {
                actions.put("onStart", startActs);
            }
        }
        
        // Complete actions (1)
        MapleData completeActData = actData.getChildByPath("1");
        if (completeActData != null) {
            Map<String, Object> completeActs = convertActionSet(completeActData);
            if (!completeActs.isEmpty()) {
                actions.put("onComplete", completeActs);
            }
        }
        
        return actions;
    }
    
    private Map<String, Object> convertActionSet(MapleData actData) {
        Map<String, Object> acts = new LinkedHashMap<>();
        
        for (MapleData child : actData.getChildren()) {
            String name = child.getName();
            
            switch (name) {
                case "exp":
                    acts.put("experience", MapleDataTool.getInt(child));
                    break;
                case "money":
                    acts.put("meso", MapleDataTool.getInt(child));
                    break;
                case "item":
                    acts.put("items", convertItemList(child));
                    break;
                case "skill":
                    acts.put("skills", convertSkillList(child));
                    break;
                case "nextQuest":
                    acts.put("nextQuest", MapleDataTool.getInt(child));
                    break;
                case "pop":
                    acts.put("fame", MapleDataTool.getInt(child));
                    break;
                case "buffItemID":
                    acts.put("buffItemId", MapleDataTool.getInt(child));
                    break;
                case "petskill":
                    acts.put("petSkill", MapleDataTool.getInt(child));
                    break;
                case "pettameness":
                    acts.put("petTameness", MapleDataTool.getInt(child));
                    break;
                case "petspeed":
                    acts.put("petSpeed", MapleDataTool.getInt(child));
                    break;
                case "info":
                    acts.put("info", MapleDataTool.getString(child));
                    break;
                case "lvmin":
                    acts.put("minLevel", MapleDataTool.getInt(child));
                    break;
                case "npc":
                    acts.put("npc", MapleDataTool.getInt(child));
                    break;
                case "interval":
                    acts.put("intervalMinutes", MapleDataTool.getInt(child));
                    break;
            }
        }
        
        return acts;
    }
    
    private List<Integer> convertJobList(MapleData jobData) {
        List<Integer> jobs = new ArrayList<>();
        for (MapleData job : jobData.getChildren()) {
            jobs.add(MapleDataTool.getInt(job));
        }
        return jobs;
    }
    
    private List<Map<String, Object>> convertItemList(MapleData itemData) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (MapleData itemNode : itemData.getChildren()) {
            Map<String, Object> item = new LinkedHashMap<>();
            
            int id = MapleDataTool.getInt("id", itemNode, 0);
            item.put("itemId", id);
            
            int count = MapleDataTool.getInt("count", itemNode, 1);
            if (count != 1) item.put("count", count);
            
            int job = MapleDataTool.getInt("job", itemNode, -1);
            if (job != -1) item.put("jobRestriction", job);
            
            int prop = MapleDataTool.getInt("prop", itemNode, -1);
            if (prop != -1) item.put("probability", prop);
            
            int gender = MapleDataTool.getInt("gender", itemNode, -1);
            if (gender != -1) item.put("gender", gender);
            
            String dateExpire = MapleDataTool.getString("dateExpire", itemNode, null);
            if (dateExpire != null) item.put("expirationDate", dateExpire);
            
            int potentialGrade = MapleDataTool.getInt("potentialGrade", itemNode, -1);
            if (potentialGrade != -1) item.put("potentialGrade", potentialGrade);
            
            items.add(item);
        }
        return items;
    }
    
    private List<Map<String, Object>> convertMobList(MapleData mobData) {
        List<Map<String, Object>> mobs = new ArrayList<>();
        for (MapleData mobNode : mobData.getChildren()) {
            Map<String, Object> mob = new LinkedHashMap<>();
            
            int id = MapleDataTool.getInt("id", mobNode, 0);
            mob.put("monsterId", id);
            
            int count = MapleDataTool.getInt("count", mobNode, 1);
            if (count != 1) mob.put("count", count);
            
            mobs.add(mob);
        }
        return mobs;
    }
    
    private List<Map<String, Object>> convertQuestList(MapleData questData) {
        List<Map<String, Object>> quests = new ArrayList<>();
        for (MapleData questNode : questData.getChildren()) {
            Map<String, Object> quest = new LinkedHashMap<>();
            
            int id = MapleDataTool.getInt("id", questNode, 0);
            quest.put("questId", id);
            
            int state = MapleDataTool.getInt("state", questNode, -1);
            if (state != -1) {
                String stateStr = state == 0 ? "NOT_STARTED" : 
                                  state == 1 ? "STARTED" : 
                                  state == 2 ? "COMPLETED" : "UNKNOWN";
                quest.put("state", stateStr);
            }
            
            quests.add(quest);
        }
        return quests;
    }
    
    private List<Map<String, Object>> convertSkillList(MapleData skillData) {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (MapleData skillNode : skillData.getChildren()) {
            Map<String, Object> skill = new LinkedHashMap<>();
            
            int id = MapleDataTool.getInt("id", skillNode, 0);
            skill.put("skillId", id);
            
            int skillLevel = MapleDataTool.getInt("skillLevel", skillNode, -1);
            if (skillLevel != -1) skill.put("skillLevel", skillLevel);
            
            int masterLevel = MapleDataTool.getInt("masterLevel", skillNode, -1);
            if (masterLevel != -1) skill.put("masterLevel", masterLevel);
            
            skills.add(skill);
        }
        return skills;
    }
    
    private Map<String, Object> convertPetRequirement(MapleData petData) {
        Map<String, Object> pet = new LinkedHashMap<>();
        // Pet data structure varies, implement as needed
        return pet;
    }
    
    private List<String> convertInfoExList(MapleData infoExData) {
        List<String> infoEx = new ArrayList<>();
        for (MapleData node : infoExData.getChildren()) {
            infoEx.add(MapleDataTool.getString(node));
        }
        return infoEx;
    }
    
    private List<Integer> convertBuffList(MapleData buffData) {
        List<Integer> buffs = new ArrayList<>();
        for (MapleData buff : buffData.getChildren()) {
            buffs.add(MapleDataTool.getInt(buff));
        }
        return buffs;
    }
    
    private void saveQuestJson(int questId, Map<String, Object> questJson) {
        try {
            // Group quests into subdirectories by range for better organization
            int rangeStart = (questId / 1000) * 1000;
            String subdir = OUTPUT_PATH + rangeStart + "/";
            new File(subdir).mkdirs();
            
            String filename = subdir + questId + ".json";
            try (FileWriter writer = new FileWriter(filename)) {
                gson.toJson(questJson, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save quest " + questId, e);
        }
    }
}
