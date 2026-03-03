# Quest JSON Conversion

This branch implements a new JSON-based quest data format to replace the existing XML-based quest system.

## Overview

The new JSON format provides:
- **Better readability** - Human-friendly property names instead of numeric codes
- **Clearer structure** - Organized into logical sections (info, requirements, actions)
- **Self-documenting** - Property names like `minLevel` instead of `lvmin`
- **Easier maintenance** - Standard JSON format supported by all tools

## Quest Data Structure

### JSON Format

Quests are stored in `/wz/Quest.wz/json/` organized by quest ID range:
- `/json/1000/1000.json` - Quest 1000
- `/json/10000/10001.json` - Quest 10001

### Quest JSON Schema

```json
{
  "questId": 1000,
  "info": {
    "name": "Quest Name",
    "parent": "Parent Quest Name",
    "descriptions": {
      "notStarted": "Description before quest starts",
      "inProgress": "Description while quest is active",
      "completed": "Description after completing quest"
    },
    "area": 20,
    "order": 1,
    "timeLimit": 3600,
    "autoStart": true,
    "autoPreComplete": false,
    "autoComplete": false,
    "medalItemId": 1142000
  },
  "requirements": {
    "start": {
      "npc": 2101,
      "minLevel": 10,
      "maxLevel": 30,
      "jobs": [0, 100, 200],
      "items": [
        {"itemId": 1042003, "count": 1}
      ],
      "monsters": [
        {"monsterId": 9300003, "count": 50}
      ],
      "prerequisiteQuests": [
        {"questId": 10000, "state": "COMPLETED"}
      ],
      "meso": 1000,
      "repeatIntervalMinutes": 120,
      "startDate": "2009060100",
      "endDate": "2009082600",
      "startScript": "quest_script",
      "infoNumber": 10000,
      "infoEx": ["value1", "value2"]
    },
    "complete": {
      "npc": 2100,
      "items": [
        {"itemId": 4031001, "count": 1}
      ]
    }
  },
  "actions": {
    "onStart": {
      "items": [
        {"itemId": 4031001, "count": 1}
      ]
    },
    "onComplete": {
      "experience": 500,
      "meso": 1000,
      "fame": 1,
      "items": [
        {
          "itemId": 1072366,
          "count": 1,
          "jobRestriction": 63,
          "expirationDate": "2009090923",
          "probability": 100
        }
      ],
      "skills": [
        {"skillId": 1001003, "skillLevel": 1, "masterLevel": 10}
      ],
      "nextQuest": 1001,
      "buffItemId": 2022000,
      "petTameness": 100
    }
  }
}
```

## Property Mapping (Old XML → New JSON)

### Requirements
| XML Name | JSON Name | Description |
|----------|-----------|-------------|
| `npc` | `npc` | Required NPC ID |
| `lvmin` | `minLevel` | Minimum level required |
| `lvmax` | `maxLevel` | Maximum level allowed |
| `job` | `jobs` | List of allowed job IDs |
| `item` | `items` | Required items |
| `mob` | `monsters` | Monsters to defeat |
| `quest` | `prerequisiteQuests` | Required quest states |
| `questComplete` | `completedQuests` | Quests that must be completed |
| `money` | `meso` | Meso requirement |
| `interval` | `repeatIntervalMinutes` | Repeat cooldown in minutes |
| `start` | `startDate` | Quest availability start date |
| `end` | `endDate` | Quest availability end date |
| `startscript` / `endscript` | `startScript` / `endScript` | Script requirements |
| `pettamenessmin` | `minPetTameness` | Minimum pet tameness |
| `mbmin` | `minMonsterBookCards` | Minimum monster book cards |
| `infoNumber` | `infoNumber` | Info number for quest tracking |
| `infoex` | `infoEx` | Extended info values |
| `buff` | `requiredBuffs` | Required buffs |
| `exceptbuff` | `forbiddenBuffs` | Forbidden buffs |

### Actions
| XML Name | JSON Name | Description |
|----------|-----------|-------------|
| `exp` | `experience` | Experience reward |
| `money` | `meso` | Meso reward |
| `item` | `items` | Item rewards |
| `skill` | `skills` | Skill rewards |
| `nextQuest` | `nextQuest` | Next quest to start |
| `pop` | `fame` | Fame reward |
| `buffItemID` | `buffItemId` | Buff item to apply |
| `petskill` | `petSkill` | Pet skill reward |
| `pettameness` | `petTameness` | Pet tameness change |
| `petspeed` | `petSpeed` | Pet speed change |

### Quest States
| Old Value | New Value | Description |
|-----------|-----------|-------------|
| `0` | `NOT_STARTED` | Quest not started |
| `1` | `STARTED` | Quest in progress |
| `2` | `COMPLETED` | Quest completed |

### Quest Phases
| Old Value | New Value | Description |
|-----------|-----------|-------------|
| `0` | `start` | Start requirements/actions |
| `1` | `complete` | Complete requirements/actions |

## Configuration

Enable JSON quest loading in `config.yaml`:

```yaml
server:
  USE_JSON_QUESTS: true  # Enable JSON quest loading
```

**Note:** The system will fall back to XML if JSON files don't exist.

## Converting Quests from XML to JSON

### Using the Converter Tool

1. Ensure Gson dependency is in `pom.xml` (already added)
2. Run the converter:
```bash
java -cp "target/classes:target/dependency/*" tools.QuestXmlToJsonConverter.QuestXmlToJsonConverter
```

3. JSON files will be generated in `/wz/Quest.wz/json/`

### Manual Conversion

See the example files in `/wz/Quest.wz/json/` for reference:
- `1000/1000.json` - Simple quest example
- `10000/10001.json` - Complex quest with multiple requirements

## Files Modified

### Core System
- `src/server/quest/MapleQuest.java` - Added JSON loading support
- `src/config/ServerConfig.java` - Added USE_JSON_QUESTS flag
- `config.yaml` - Added USE_JSON_QUESTS configuration

### New Files
- `src/server/quest/json/QuestJsonData.java` - JSON data models
- `src/server/quest/json/JsonQuestLoader.java` - JSON quest loader
- `tools/QuestXmlToJsonConverter/QuestXmlToJsonConverter.java` - XML to JSON converter
- `wz/Quest.wz/json/` - JSON quest data directory

### Dependencies
- `pom.xml` - Added Gson 2.10.1 for JSON parsing

## Benefits

1. **Readability** - Quest data is much easier to read and understand
2. **Maintainability** - Easier to modify and debug quests
3. **Tooling** - Better IDE support, syntax highlighting, validation
4. **Documentation** - Self-documenting format with clear property names
5. **Flexibility** - Easy to add new fields without breaking existing data
6. **Version Control** - Better diff visualization in git

## Testing

To test JSON quest loading:

1. Enable in config: `USE_JSON_QUESTS: true`
2. Start the server
3. Check console output: `[QuestSystem] Loaded X quests (using JSON format)`
4. Test quests 1000 and 10001 (sample JSON files provided)

## Migration Strategy

**Phase 1 (Current):**
- JSON loading is optional
- System falls back to XML if JSON files don't exist
- Both formats can coexist

**Phase 2 (Future):**
- Run converter on all quests
- Verify all quests load correctly
- Enable JSON by default

**Phase 3 (Final):**
- Remove XML loading code
- Keep only JSON system

## Notes

- Quest IDs remain unchanged
- XML files are not modified during conversion
- JSON files are organized in subdirectories (1000s grouping) for better organization
- Backwards compatible - can switch back to XML anytime by disabling the flag
