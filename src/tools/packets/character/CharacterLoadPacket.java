package tools.packets.character;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import client.MapleCharacter;
import client.MapleCharacter.SkillEntry;
import client.MapleQuestStatus;
import client.MapleRing;
import client.Skill;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.MaplePet;
import client.newyear.NewYearCardRecord;
import com.google.flatbuffers.FlatBufferBuilder;
import constants.game.ExpTable;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import net.server.PlayerCoolDownValueHolder;
import protocol.generated.maple.character.AreaInfoEntry;
import protocol.generated.maple.character.CharacterAreaInfo;
import protocol.generated.maple.character.CharacterInfo;
import protocol.generated.maple.character.CharacterInventory;
import protocol.generated.maple.character.CharacterLoad;
import protocol.generated.maple.character.CharacterLook;
import protocol.generated.maple.character.CharacterMonsterBookInfo;
import protocol.generated.maple.character.CharacterNewYearInfo;
import protocol.generated.maple.character.CharacterQuestInfo;
import protocol.generated.maple.character.CharacterRingInfo;
import protocol.generated.maple.character.CharacterSkill;
import protocol.generated.maple.character.CharacterSkillInfo;
import protocol.generated.maple.character.CharacterStats;
import protocol.generated.maple.character.CharacterTeleportRockInfo;
import protocol.generated.maple.character.CrushRing;
import protocol.generated.maple.character.EquipItemData;
import protocol.generated.maple.character.EquipSlotItem;
import protocol.generated.maple.character.EquipStats;
import protocol.generated.maple.character.FriendshipRing;
import protocol.generated.maple.character.InventoryItem;
import protocol.generated.maple.character.InventorySlotLimits;
import protocol.generated.maple.character.ItemDetail;
import protocol.generated.maple.character.MarriageRing;
import protocol.generated.maple.character.MonsterCard;
import protocol.generated.maple.character.NewYearCard;
import protocol.generated.maple.character.NormalItemData;
import protocol.generated.maple.character.PetItemData;
import protocol.generated.maple.character.QuestCompletedEntry;
import protocol.generated.maple.character.QuestStartedEntry;
import protocol.generated.maple.character.SkillCooldown;
import protocol.generated.maple.common.Gender;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.MapleItemInformationProvider;

/**
 * Builds the FlatBuffers character-load message that replaces the legacy
 * MaplePacketCreator.getCharInfo/addCharacterInfo/addCharLook/addCharStats
 * byte stream sent by PlayerLoggedinHandler.
 */
public class CharacterLoadPacket {

    public static byte[] characterLoad(MapleCharacter chr, int channel) {
        FlatBufferBuilder builder = new FlatBufferBuilder(4096);

        int character = buildCharacterInfo(builder, chr);

        CharacterLoad.startCharacterLoad(builder);
        CharacterLoad.addChannel(builder, channel);
        CharacterLoad.addCharacter(builder, character);
        CharacterLoad.addTimestamp(builder, System.currentTimeMillis());
        int load = CharacterLoad.endCharacterLoad(builder);

        Packet.startPacket(builder);
        Packet.addServerMessage(builder, load);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_character_CharacterLoad);
        int packet = Packet.endPacket(builder);
        Packet.finishPacketBuffer(builder, packet);
        System.out.println("[FlatBuffers->CharacterLoad] bytes=" + builder.sizedByteArray().length);
        return builder.sizedByteArray();
    }

    static int buildCharacterInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        int stats = buildStats(builder, chr);
        int look = buildLook(builder, chr);
        int linkedName = chr.getLinkedName() == null ? 0 : builder.createString(chr.getLinkedName());
        int inventory = buildInventory(builder, chr);
        int skills = buildSkillInfo(builder, chr);
        int quests = buildQuestInfo(builder, chr);
        int rings = buildRingInfo(builder, chr);
        int teleportRocks = buildTeleportRockInfo(builder, chr);
        int monsterBook = buildMonsterBookInfo(builder, chr);
        int newYear = buildNewYearInfo(builder, chr);
        int areaInfo = buildAreaInfo(builder, chr);

        CharacterInfo.startCharacterInfo(builder);
        CharacterInfo.addAreaInfo(builder, areaInfo);
        CharacterInfo.addNewYear(builder, newYear);
        CharacterInfo.addMonsterBook(builder, monsterBook);
        CharacterInfo.addTeleportRocks(builder, teleportRocks);
        CharacterInfo.addRings(builder, rings);
        CharacterInfo.addQuests(builder, quests);
        CharacterInfo.addSkills(builder, skills);
        CharacterInfo.addInventory(builder, inventory);
        if (linkedName != 0) {
            CharacterInfo.addLinkedName(builder, linkedName);
        }
        CharacterInfo.addLook(builder, look);
        CharacterInfo.addStats(builder, stats);
        CharacterInfo.addBuddyCapacity(builder, chr.getBuddylist().getCapacity());
        return CharacterInfo.endCharacterInfo(builder);
    }

    static int buildStats(FlatBufferBuilder builder, MapleCharacter chr) {
        int name = builder.createString(chr.getName());
        long[] petIds = new long[3];
        for (int i = 0; i < 3; i++) {
            MaplePet pet = chr.getPet(i);
            petIds[i] = pet != null ? pet.getUniqueId() : 0L;
        }
        int petIdsOffset = CharacterStats.createPetIdsVector(builder, petIds);

        CharacterStats.startCharacterStats(builder);
        CharacterStats.addCharacterId(builder, chr.getId());
        CharacterStats.addName(builder, name);
        CharacterStats.addGender(builder, (byte) (chr.getGender() == 0 ? Gender.Male : Gender.Female));
        CharacterStats.addSkinId(builder, chr.getSkinColor().getId());
        CharacterStats.addFace(builder, chr.getFace());
        CharacterStats.addHair(builder, chr.getHair());
        CharacterStats.addPetIds(builder, petIdsOffset);
        CharacterStats.addLevel(builder, chr.getLevel());
        CharacterStats.addJob(builder, (short) chr.getJob().getId());
        CharacterStats.addStrStat(builder, (short) chr.getStr());
        CharacterStats.addDexStat(builder, (short) chr.getDex());
        CharacterStats.addIntStat(builder, (short) chr.getInt());
        CharacterStats.addLukStat(builder, (short) chr.getLuk());
        CharacterStats.addHp(builder, (short) chr.getHp());
        CharacterStats.addMaxHp(builder, (short) chr.getClientMaxHp());
        CharacterStats.addMp(builder, (short) chr.getMp());
        CharacterStats.addMaxMp(builder, (short) chr.getClientMaxMp());
        CharacterStats.addRemainingAp(builder, (short) chr.getRemainingAp());
        // Note: legacy per-job SP table (GameConstants.hasSPTable) is not modeled here;
        // the C++ client never consumed that variant either, only a flat remaining SP short.
        CharacterStats.addRemainingSp(builder, (short) chr.getRemainingSp());
        CharacterStats.addExp(builder, chr.getExp());
        CharacterStats.addFame(builder, (short) chr.getFame());
        CharacterStats.addGachaExp(builder, chr.getGachaExp());
        CharacterStats.addMapId(builder, chr.getMapId());
        CharacterStats.addSpawnPoint(builder, chr.getInitialSpawnpoint());
        return CharacterStats.endCharacterStats(builder);
    }

    static int buildLook(FlatBufferBuilder builder, MapleCharacter chr) {
        MapleInventory equip = chr.getInventory(MapleInventoryType.EQUIPPED);
        Collection<Item> wearable = MapleItemInformationProvider.getInstance().canWearEquipment(chr, equip.list());
        Map<Short, Integer> myEquip = new java.util.LinkedHashMap<>();
        Map<Short, Integer> maskedEquip = new java.util.LinkedHashMap<>();
        for (Item item : wearable) {
            short pos = (short) ((byte) (item.getPosition() * -1));
            if (pos < 100 && myEquip.get(pos) == null) {
                myEquip.put(pos, item.getItemId());
            } else if (pos > 100 && pos != 111) {
                pos -= 100;
                if (myEquip.get(pos) != null) {
                    maskedEquip.put(pos, myEquip.get(pos));
                }
                myEquip.put(pos, item.getItemId());
            } else if (myEquip.get(pos) != null) {
                maskedEquip.put(pos, item.getItemId());
            }
        }

        int[] equipsOffsets = new int[myEquip.size()];
        int i = 0;
        for (Entry<Short, Integer> entry : myEquip.entrySet()) {
            equipsOffsets[i++] = EquipSlotItem.createEquipSlotItem(builder, entry.getKey().byteValue(), entry.getValue());
        }
        int equipsVector = CharacterLook.createEquipsVector(builder, equipsOffsets);

        int[] maskedOffsets = new int[maskedEquip.size()];
        i = 0;
        for (Entry<Short, Integer> entry : maskedEquip.entrySet()) {
            maskedOffsets[i++] = EquipSlotItem.createEquipSlotItem(builder, entry.getKey().byteValue(), entry.getValue());
        }
        int maskedVector = CharacterLook.createMaskedEquipsVector(builder, maskedOffsets);

        Item cWeapon = equip.getItem((short) -111);
        int[] petItemIds = new int[3];
        for (int p = 0; p < 3; p++) {
            petItemIds[p] = chr.getPet(p) != null ? chr.getPet(p).getItemId() : 0;
        }
        int petItemIdsVector = CharacterLook.createPetItemIdsVector(builder, petItemIds);

        CharacterLook.startCharacterLook(builder);
        CharacterLook.addGender(builder, (byte) (chr.getGender() == 0 ? Gender.Male : Gender.Female));
        CharacterLook.addSkinId(builder, chr.getSkinColor().getId());
        CharacterLook.addFace(builder, chr.getFace());
        CharacterLook.addHair(builder, chr.getHair());
        CharacterLook.addEquips(builder, equipsVector);
        CharacterLook.addMaskedEquips(builder, maskedVector);
        CharacterLook.addWeaponStickerItemId(builder, cWeapon != null ? cWeapon.getItemId() : 0);
        CharacterLook.addPetItemIds(builder, petItemIdsVector);
        return CharacterLook.endCharacterLook(builder);
    }

    private static int buildItem(FlatBufferBuilder builder, Item item) {
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        boolean isCash = ii.isCash(item.getItemId());
        boolean isPet = item.getPetId() > -1;
        Equip equipItem = null;
        boolean isRing = false;
        if (item.getItemType() == 1) {
            equipItem = (Equip) item;
            isRing = equipItem.getRingId() > -1;
        }

        long cashId = 0L;
        if (isCash) {
            cashId = isPet ? item.getPetId() : isRing ? equipItem.getRingId() : item.getCashId();
        }

        byte detailType;
        int detailOffset;
        if (isPet) {
            MaplePet pet = item.getPet();
            int nameOffset = builder.createString(pet.getName());
            PetItemData.startPetItemData(builder);
            PetItemData.addName(builder, nameOffset);
            PetItemData.addLevel(builder, pet.getLevel());
            PetItemData.addCloseness(builder, (short) pet.getCloseness());
            PetItemData.addFullness(builder, pet.getFullness());
            PetItemData.addPetFlag(builder, pet.getPetFlag());
            detailOffset = PetItemData.endPetItemData(builder);
            detailType = ItemDetail.PetItemData;
        } else if (equipItem != null) {
            int ownerOffset = builder.createString(equipItem.getOwner());

            int itemLevel = 0;
            short itemExp = 0;
            int vicious = 0;

            if (!isCash) {
                itemLevel = equipItem.getItemLevel();
                long expNibble = (long) ExpTable.getExpNeededForLevel(ii.getEquipLevelReq(item.getItemId())) * equipItem.getItemExp();
                expNibble /= ExpTable.getEquipExpNeededForLevel(itemLevel);
                itemExp = (short) expNibble;
                vicious = equipItem.getVicious();
            }

            EquipItemData.startEquipItemData(builder);
            EquipItemData.addUpgradeSlots(builder, equipItem.getUpgradeSlots());
            EquipItemData.addLevel(builder, equipItem.getLevel());

            int statsOffset = EquipStats.createEquipStats(builder,
                    (short) equipItem.getStr(),
                    (short) equipItem.getDex(),
                    (short) equipItem.getInt(),
                    (short) equipItem.getLuk(),
                    (short) equipItem.getHp(),
                    (short) equipItem.getMp(),
                    (short) equipItem.getWatk(),
                    (short) equipItem.getMatk(),
                    (short) equipItem.getWdef(),
                    (short) equipItem.getMdef(),
                    (short) equipItem.getAcc(),
                    (short) equipItem.getAvoid(),
                    (short) equipItem.getHands(),
                    (short) equipItem.getSpeed(),
                    (short) equipItem.getJump()
            );

            EquipItemData.addStats(builder, statsOffset);
            EquipItemData.addOwner(builder, ownerOffset);
            EquipItemData.addFlag(builder, equipItem.getFlag());
            EquipItemData.addItemLevel(builder, itemLevel);
            EquipItemData.addItemExp(builder, itemExp);
            EquipItemData.addVicious(builder, vicious);

            detailOffset = EquipItemData.endEquipItemData(builder);
            detailType = ItemDetail.EquipItemData;
        } else {
            int ownerOffset = builder.createString(item.getOwner());
            boolean rechargeable = ItemConstants.isRechargeable(item.getItemId());
            NormalItemData.startNormalItemData(builder);
            NormalItemData.addQuantity(builder, item.getQuantity());
            NormalItemData.addOwner(builder, ownerOffset);
            NormalItemData.addFlag(builder, item.getFlag());
            NormalItemData.addRechargeable(builder, rechargeable);
            detailOffset = NormalItemData.endNormalItemData(builder);
            detailType = ItemDetail.NormalItemData;
        }

        InventoryItem.startInventoryItem(builder);
        InventoryItem.addSlot(builder, item.getPosition());
        InventoryItem.addItemId(builder, item.getItemId());
        InventoryItem.addCash(builder, isCash);
        InventoryItem.addCashId(builder, cashId);
        InventoryItem.addExpiration(builder, item.getExpiration());
        InventoryItem.addDetailType(builder, detailType);
        InventoryItem.addDetail(builder, detailOffset);
        return InventoryItem.endInventoryItem(builder);
    }

    private static int buildItemVector(FlatBufferBuilder builder, Collection<Item> items) {
        int[] offsets = new int[items.size()];
        int i = 0;
        for (Item item : items) {
            offsets[i++] = buildItem(builder, item);
        }
        return CharacterInventory.createEquipVector(builder, offsets);
    }

    private static int buildInventory(FlatBufferBuilder builder, MapleCharacter chr) {
        MapleInventory equippedInv = chr.getInventory(MapleInventoryType.EQUIPPED);
        Collection<Item> equippedAll = equippedInv.list();
        java.util.List<Item> equipped = new java.util.ArrayList<>(equippedAll.size());
        java.util.List<Item> equippedCash = new java.util.ArrayList<>(equippedAll.size());
        for (Item item : equippedAll) {
            if (item.getPosition() <= -100) {
                equippedCash.add(item);
            } else {
                equipped.add(item);
            }
        }

        int equippedOffset = buildItemVector(builder, equipped);
        int equippedCashOffset = buildItemVector(builder, equippedCash);
        int equipOffset = buildItemVector(builder, chr.getInventory(MapleInventoryType.EQUIP).list());
        int useOffset = buildItemVector(builder, chr.getInventory(MapleInventoryType.USE).list());
        int setupOffset = buildItemVector(builder, chr.getInventory(MapleInventoryType.SETUP).list());
        int etcOffset = buildItemVector(builder, chr.getInventory(MapleInventoryType.ETC).list());
        int cashOffset = buildItemVector(builder, chr.getInventory(MapleInventoryType.CASH).list());

        InventorySlotLimits.startInventorySlotLimits(builder);
        InventorySlotLimits.addEquip(builder, chr.getInventory(MapleInventoryType.EQUIP).getSlotLimit());
        InventorySlotLimits.addUse_(builder, chr.getInventory(MapleInventoryType.USE).getSlotLimit());
        InventorySlotLimits.addSetup(builder, chr.getInventory(MapleInventoryType.SETUP).getSlotLimit());
        InventorySlotLimits.addEtc(builder, chr.getInventory(MapleInventoryType.ETC).getSlotLimit());
        InventorySlotLimits.addCash(builder, chr.getInventory(MapleInventoryType.CASH).getSlotLimit());
        int slotLimits = InventorySlotLimits.endInventorySlotLimits(builder);

        CharacterInventory.startCharacterInventory(builder);
        CharacterInventory.addMeso(builder, chr.getMeso());
        CharacterInventory.addSlotLimits(builder, slotLimits);
        CharacterInventory.addEquipped(builder, equippedOffset);
        CharacterInventory.addEquippedCash(builder, equippedCashOffset);
        CharacterInventory.addEquip(builder, equipOffset);
        CharacterInventory.addUse_(builder, useOffset);
        CharacterInventory.addSetup(builder, setupOffset);
        CharacterInventory.addEtc(builder, etcOffset);
        CharacterInventory.addCash(builder, cashOffset);
        return CharacterInventory.endCharacterInventory(builder);
    }

    private static int buildSkillInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        Map<Skill, SkillEntry> skillMap = chr.getSkills();
        java.util.List<Integer> skillOffsets = new java.util.ArrayList<>(skillMap.size());
        for (Entry<Skill, SkillEntry> entry : skillMap.entrySet()) {
            Skill skill = entry.getKey();
            if (GameConstants.isHiddenSkills(skill.getId())) {
                continue;
            }
            SkillEntry value = entry.getValue();
            CharacterSkill.startCharacterSkill(builder);
            CharacterSkill.addSkillId(builder, skill.getId());
            CharacterSkill.addLevel(builder, value.skillevel);
            CharacterSkill.addMasterLevel(builder, skill.isFourthJob() ? value.masterlevel : 0);
            CharacterSkill.addExpiration(builder, value.expiration);
            skillOffsets.add(CharacterSkill.endCharacterSkill(builder));
        }
        int[] skillArr = skillOffsets.stream().mapToInt(Integer::intValue).toArray();
        int skillsVector = CharacterSkillInfo.createSkillsVector(builder, skillArr);

        Collection<PlayerCoolDownValueHolder> cooldowns = chr.getAllCooldowns();
        int[] cooldownOffsets = new int[cooldowns.size()];
        int i = 0;
        for (PlayerCoolDownValueHolder cooling : cooldowns) {
            int timeLeft = (int) ((cooling.length + cooling.startTime - System.currentTimeMillis()) / 1000);
            SkillCooldown.startSkillCooldown(builder);
            SkillCooldown.addSkillId(builder, cooling.skillId);
            SkillCooldown.addTimeLeftSeconds(builder, (short) timeLeft);
            cooldownOffsets[i++] = SkillCooldown.endSkillCooldown(builder);
        }
        int cooldownsVector = CharacterSkillInfo.createCooldownsVector(builder, cooldownOffsets);

        CharacterSkillInfo.startCharacterSkillInfo(builder);
        CharacterSkillInfo.addSkills(builder, skillsVector);
        CharacterSkillInfo.addCooldowns(builder, cooldownsVector);
        return CharacterSkillInfo.endCharacterSkillInfo(builder);
    }

    private static int buildQuestInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        List<MapleQuestStatus> started = chr.getStartedQuests();
        java.util.List<int[]> pending = new java.util.ArrayList<>();
        java.util.List<Integer> startedOffsets = new java.util.ArrayList<>();
        for (MapleQuestStatus qs : started) {
            int progressOffset = builder.createString(qs.getProgressData());
            QuestStartedEntry.startQuestStartedEntry(builder);
            QuestStartedEntry.addQuestId(builder, (short) qs.getQuest().getId());
            QuestStartedEntry.addProgress(builder, progressOffset);
            startedOffsets.add(QuestStartedEntry.endQuestStartedEntry(builder));

            short infoNumber = qs.getInfoNumber();
            if (infoNumber > 0) {
                MapleQuestStatus iqs = chr.getQuest(infoNumber);
                int infoProgressOffset = builder.createString(iqs.getProgressData());
                QuestStartedEntry.startQuestStartedEntry(builder);
                QuestStartedEntry.addQuestId(builder, infoNumber);
                QuestStartedEntry.addProgress(builder, infoProgressOffset);
                startedOffsets.add(QuestStartedEntry.endQuestStartedEntry(builder));
            }
        }
        int[] startedArr = startedOffsets.stream().mapToInt(Integer::intValue).toArray();
        int startedVector = CharacterQuestInfo.createStartedVector(builder, startedArr);

        List<MapleQuestStatus> completed = chr.getCompletedQuests();
        int[] completedOffsets = new int[completed.size()];
        int i = 0;
        for (MapleQuestStatus qs : completed) {
            QuestCompletedEntry.startQuestCompletedEntry(builder);
            QuestCompletedEntry.addQuestId(builder, (short) qs.getQuest().getId());
            QuestCompletedEntry.addCompletionTime(builder, qs.getCompletionTime());
            completedOffsets[i++] = QuestCompletedEntry.endQuestCompletedEntry(builder);
        }
        int completedVector = CharacterQuestInfo.createCompletedVector(builder, completedOffsets);

        CharacterQuestInfo.startCharacterQuestInfo(builder);
        CharacterQuestInfo.addStarted(builder, startedVector);
        CharacterQuestInfo.addCompleted(builder, completedVector);
        return CharacterQuestInfo.endCharacterQuestInfo(builder);
    }

    private static int buildRingInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        List<MapleRing> crush = chr.getCrushRings();
        int[] crushOffsets = new int[crush.size()];
        int i = 0;
        for (MapleRing ring : crush) {
            int nameOffset = builder.createString(ring.getPartnerName());
            CrushRing.startCrushRing(builder);
            CrushRing.addPartnerCharId(builder, ring.getPartnerChrId());
            CrushRing.addPartnerName(builder, nameOffset);
            CrushRing.addRingId(builder, ring.getRingId());
            CrushRing.addPartnerRingId(builder, ring.getPartnerRingId());
            crushOffsets[i++] = CrushRing.endCrushRing(builder);
        }
        int crushVector = CharacterRingInfo.createCrushRingsVector(builder, crushOffsets);

        List<MapleRing> friendship = chr.getFriendshipRings();
        int[] friendshipOffsets = new int[friendship.size()];
        i = 0;
        for (MapleRing ring : friendship) {
            int nameOffset = builder.createString(ring.getPartnerName());
            FriendshipRing.startFriendshipRing(builder);
            FriendshipRing.addPartnerCharId(builder, ring.getPartnerChrId());
            FriendshipRing.addPartnerName(builder, nameOffset);
            FriendshipRing.addRingId(builder, ring.getRingId());
            FriendshipRing.addPartnerRingId(builder, ring.getPartnerRingId());
            FriendshipRing.addItemId(builder, ring.getItemId());
            friendshipOffsets[i++] = FriendshipRing.endFriendshipRing(builder);
        }
        int friendshipVector = CharacterRingInfo.createFriendshipRingsVector(builder, friendshipOffsets);

        int marriage = 0;
        if (chr.getPartnerId() > 0) {
            MapleRing marriageRing = chr.getMarriageRing();
            int ownCharId = chr.getGender() == 0 ? chr.getId() : chr.getPartnerId();
            int partnerCharId = chr.getGender() == 0 ? chr.getPartnerId() : chr.getId();
            int ringItemId = marriageRing != null ? marriageRing.getItemId() : 0;
            MarriageRing.startMarriageRing(builder);
            MarriageRing.addRelationshipId(builder, chr.getRelationshipId());
            MarriageRing.addCharId(builder, ownCharId);
            MarriageRing.addPartnerCharId(builder, partnerCharId);
            MarriageRing.addRingItemId(builder, ringItemId);
            MarriageRing.addPartnerRingItemId(builder, ringItemId);
            marriage = MarriageRing.endMarriageRing(builder);
        }

        CharacterRingInfo.startCharacterRingInfo(builder);
        CharacterRingInfo.addCrushRings(builder, crushVector);
        CharacterRingInfo.addFriendshipRings(builder, friendshipVector);
        if (marriage != 0) {
            CharacterRingInfo.addMarriage(builder, marriage);
        }
        return CharacterRingInfo.endCharacterRingInfo(builder);
    }

    private static int buildTeleportRockInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        List<Integer> tele = chr.getTrockMaps();
        List<Integer> viptele = chr.getVipTrockMaps();
        int[] locations = new int[5];
        for (int i = 0; i < 5; i++) {
            locations[i] = tele.get(i);
        }
        int[] vipLocations = new int[10];
        for (int i = 0; i < 10; i++) {
            vipLocations[i] = viptele.get(i);
        }
        int locationsOffset = CharacterTeleportRockInfo.createLocationsVector(builder, locations);
        int vipLocationsOffset = CharacterTeleportRockInfo.createVipLocationsVector(builder, vipLocations);

        CharacterTeleportRockInfo.startCharacterTeleportRockInfo(builder);
        CharacterTeleportRockInfo.addLocations(builder, locationsOffset);
        CharacterTeleportRockInfo.addVipLocations(builder, vipLocationsOffset);
        return CharacterTeleportRockInfo.endCharacterTeleportRockInfo(builder);
    }

    private static int buildMonsterBookInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        Map<Integer, Integer> cards = chr.getMonsterBook().getCards();
        int[] cardOffsets = new int[cards.size()];
        int i = 0;
        for (Entry<Integer, Integer> entry : cards.entrySet()) {
            MonsterCard.startMonsterCard(builder);
            MonsterCard.addCardId(builder, (short) (entry.getKey() % 10000));
            MonsterCard.addLevel(builder, entry.getValue());
            cardOffsets[i++] = MonsterCard.endMonsterCard(builder);
        }
        int cardsVector = CharacterMonsterBookInfo.createCardsVector(builder, cardOffsets);

        CharacterMonsterBookInfo.startCharacterMonsterBookInfo(builder);
        CharacterMonsterBookInfo.addCover(builder, chr.getMonsterBookCover());
        CharacterMonsterBookInfo.addCards(builder, cardsVector);
        return CharacterMonsterBookInfo.endCharacterMonsterBookInfo(builder);
    }

    private static int buildNewYearInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        Set<NewYearCardRecord> received = chr.getReceivedNewYearRecords();
        int[] offsets = new int[received.size()];
        int i = 0;
        for (NewYearCardRecord nyc : received) {
            int senderNameOffset = builder.createString(nyc.getSenderName());
            int receiverNameOffset = builder.createString(nyc.getReceiverName());
            int messageOffset = builder.createString(nyc.getMessage());
            NewYearCard.startNewYearCard(builder);
            NewYearCard.addCardId(builder, nyc.getId());
            NewYearCard.addSenderId(builder, nyc.getSenderId());
            NewYearCard.addSenderName(builder, senderNameOffset);
            NewYearCard.addSenderDiscarded(builder, nyc.isSenderCardDiscarded());
            NewYearCard.addDateSent(builder, nyc.getDateSent());
            NewYearCard.addReceiverId(builder, nyc.getReceiverId());
            NewYearCard.addReceiverName(builder, receiverNameOffset);
            NewYearCard.addReceiverDiscarded(builder, nyc.isReceiverCardDiscarded());
            NewYearCard.addReceiverReceived(builder, nyc.isReceiverCardReceived());
            NewYearCard.addDateReceived(builder, nyc.getDateReceived());
            NewYearCard.addMessage(builder, messageOffset);
            offsets[i++] = NewYearCard.endNewYearCard(builder);
        }
        int cardsVector = CharacterNewYearInfo.createCardsVector(builder, offsets);

        CharacterNewYearInfo.startCharacterNewYearInfo(builder);
        CharacterNewYearInfo.addCards(builder, cardsVector);
        return CharacterNewYearInfo.endCharacterNewYearInfo(builder);
    }

    private static int buildAreaInfo(FlatBufferBuilder builder, MapleCharacter chr) {
        Map<Short, String> areaInfos = chr.getAreaInfos();
        int[] offsets = new int[areaInfos.size()];
        int i = 0;
        for (Entry<Short, String> entry : areaInfos.entrySet()) {
            int valueOffset = builder.createString(entry.getValue());
            AreaInfoEntry.startAreaInfoEntry(builder);
            AreaInfoEntry.addArea(builder, entry.getKey());
            AreaInfoEntry.addValue(builder, valueOffset);
            offsets[i++] = AreaInfoEntry.endAreaInfoEntry(builder);
        }
        int entriesVector = CharacterAreaInfo.createEntriesVector(builder, offsets);

        CharacterAreaInfo.startCharacterAreaInfo(builder);
        CharacterAreaInfo.addEntries(builder, entriesVector);
        return CharacterAreaInfo.endCharacterAreaInfo(builder);
    }
}
