package net.server.channel.handlers;

import client.MapleBuffStat;
import client.MapleCharacter;
import client.MapleClient;
import client.MapleJob;
import client.SkillFactory;
import client.Skill;
import client.inventory.Item;
import client.inventory.Equip;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.MapleWeaponType;
import client.inventory.manipulator.MapleInventoryManipulator;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Crossbowman;
import constants.skills.Gunslinger;
import constants.skills.Hunter;
import constants.skills.Marauder;
import constants.skills.NightWalker;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;
import client.status.MonsterStatus;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.server.PlayerBuffValueHolder;
import protocol.generated.maple.combat.AttackRequest;
import protocol.generated.maple.combat.AttackStatus;
import server.MapleItemInformationProvider;
import server.MapleStatEffect;
import server.life.MapleMonster;
import server.maps.MapleMap;
import tools.MaplePacketCreator;
import tools.Randomizer;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.packets.combat.CombatPackets;

/**
 * Server-authoritative basic ranged and explicitly allowlisted multi-shot skills.
 *
 * Pipeline: validate request -> select targets -> roll damage -> mutate through the
 * existing applyAttack (which owns HP, death, drops and rewards) -> replicate result.
 * Projectile travel is purely client presentation; the hit is resolved immediately.
 */
public final class FlatBufferAttackHandler extends AbstractDealDamageHandler {

    private static final long MIN_ATTACK_INTERVAL_MS = 300;
    private static final int MAX_TARGETS_BASIC = 1;
    private static final double MAX_RANGE_SQ = 600000;
    private static final int MAX_VERTICAL_RANGE = 200;
    private static final int FACING_SLACK = 50;
    private static final int MAX_DAMAGE = 999999;

    private static final class RequestState {
        long lastRequestId = -1;
        long lastAcceptedAt;
    }

    private static final Map<Integer, RequestState> requestStates = new ConcurrentHashMap<>();

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        // Only reachable through handleFlatBufferAttack.
    }

    public static void forget(int characterId) {
        requestStates.remove(characterId);
    }

    public void handleFlatBufferAttack(AttackRequest request, MapleClient c) {
        if (c == null || request == null) {
            return;
        }
        MapleCharacter chr = c.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return;
        }

        long requestId = request.requestId();
        int skillId = request.skillId();

        // 1. Validation
        int status = admitRequest(chr, requestId);
        if (status != AttackStatus.Ok) {
            reject(c, chr, requestId, skillId, status);
            return;
        }
        if (!chr.isAlive() || chr.isBanned() || chr.getMap().isOwnershipRestricted(chr)) {
            reject(c, chr, requestId, skillId, AttackStatus.Rejected);
            return;
        }
        if (skillId == 0 && chr.isDarkSightActive()) {
            reject(c, chr, requestId, skillId, AttackStatus.Rejected);
            return;
        }
        if (skillId != 0 && namedShotCount(skillId) == 0) {
            reject(c, chr, requestId, skillId, AttackStatus.Unsupported);
            return;
        }
        MapleStatEffect morph = chr.getBuffEffect(MapleBuffStat.MORPH);
        if (morph != null && morph.isMorphWithoutAttack()) {
            c.disconnect(false, false);
            return;
        }

        Item weapon = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((short) -11);
        if (weapon == null) {
            reject(c, chr, requestId, skillId, AttackStatus.InvalidWeapon);
            return;
        }
        MapleWeaponType type = MapleItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
        if (!isRangedWeapon(type, weapon.getItemId())) {
            reject(c, chr, requestId, skillId, AttackStatus.InvalidWeapon);
            return;
        }

        int skillLevel = 0;
        MapleStatEffect effect = null;
        if (skillId != 0) {
            Skill skill = SkillFactory.getSkill(skillId);
            skillLevel = chr.getSkillLevel(skillId);
            if (skill == null || skillLevel <= 0 || skillLevel > skill.getMaxLevel()
                    || !GameConstants.isInJobTree(skillId, chr.getJob().getId())
                    || (request.display() > 80 && !skill.getAction())) {
                reject(c, chr, requestId, skillId, AttackStatus.Rejected);
                return;
            }
            if (!isSkillWeapon(skillId, type) || weapon.getItemId() == 1472063) {
                reject(c, chr, requestId, skillId, AttackStatus.InvalidWeapon);
                return;
            }
            effect = skill.getEffect(skillLevel);
            if (chr.skillIsCooling(skillId) || !effect.canApplyAttackCost(chr)
                    || effect.getBulletCount() != namedShotCount(skillId)) {
                reject(c, chr, requestId, skillId, AttackStatus.Rejected);
                return;
            }
        }

        boolean shadowPartner = chr.getBuffedValue(MapleBuffStat.SHADOWPARTNER) != null;
        double partnerScale = shadowPartnerScale(chr, skillId);
        short bulletCount = (short) ((effect == null ? 1 : effect.getBulletCount()) * (shadowPartner ? 2 : 1));
        short bulletConsume = (short) ((effect != null && effect.getBulletConsume() != 0
                ? effect.getBulletConsume() : (effect == null ? 1 : effect.getBulletCount()))
                * (shadowPartner ? 2 : 1));
        boolean soulArrow = chr.getBuffedValue(MapleBuffStat.SOULARROW) != null;
        boolean shadowClaw = chr.getBuffedValue(MapleBuffStat.SHADOW_CLAW) != null;
        boolean freeAmmo = soulArrow || shadowClaw;

        Item ammo = findAmmo(chr, weapon, type, freeAmmo ? 0 : bulletConsume);
        if (ammo == null && !soulArrow) {
            chr.dropMessage(5, "You do not have enough usable ammunition for this attack. Check its level requirement.");
            reject(c, chr, requestId, skillId, AttackStatus.NoAmmo);
            return;
        }

        // 2. Target selection / hit detection on authoritative map state
        MapleMap map = chr.getMap();
        boolean toleft = request.toleft();
        List<MapleMonster> targets = selectTargets(chr, map, request, toleft, MAX_TARGETS_BASIC);

        // 3. Damage calculation
        long maxDamage = effect == null ? calculateBasicMaxDamage(chr) : 0;
        boolean canCrit = canCrit(chr);
        double mastery = effect == null ? getMastery(chr, type) : 0;
        double[] bounds = effect == null ? null : calculateSkillDamageBounds(chr, type, skillId, effect);
        double accuracy = calculateAccuracy(chr, type);

        AttackInfo attack = new AttackInfo();
        attack.skill = skillId;
        attack.skilllevel = skillLevel;
        attack.stance = request.stance();
        attack.direction = toleft ? 0x80 : 0;
        attack.rangedirection = 0;
        attack.display = request.display();
        attack.speed = request.speed();
        attack.ranged = true;
        attack.magic = false;
        attack.authoritativeDamage = true;
        attack.position = new Point(chr.getPosition());
        attack.allDamage = new HashMap<>();

        List<CombatPackets.TargetResult> replicated = new ArrayList<>(targets.size());
        for (MapleMonster mob : targets) {
            double hitChance = hitChance(accuracy, chr.getLevel(), mob.getLevel(), mob.getStats().getEvasion());
            boolean immune = mob.isBuffed(MonsterStatus.WEAPON_IMMUNITY);
            List<Integer> encoded = new ArrayList<>(bulletCount);
            int[] shown = new int[bulletCount];
            boolean[] crits = new boolean[bulletCount];
            for (int hit = 0; hit < bulletCount; hit++) {
                if (shadowPartner && hit >= bulletCount / 2) {
                    int source = hit - bulletCount / 2;
                    int damage = (int) (shown[source] * partnerScale);
                    shown[hit] = damage;
                    crits[hit] = damage > 0 && crits[source];
                    encoded.add(crits[hit] ? -Integer.MAX_VALUE + damage : damage);
                    continue;
                }
                if (hitChance < 1.0 && Randomizer.nextDouble() >= hitChance) {
                    encoded.add(0);
                    continue;
                }
                boolean crit = canCrit && (effect == null ? rollCrit(chr) : rollSkillCrit(chr));
                int damage = effect == null ? rollDamage(chr, mob, maxDamage, mastery, crit)
                        : rollDamageBounds(chr, mob, bounds[0], bounds[1],
                                crit ? skillCriticalMultiplier(chr, effect) : 1.0);
                if (immune) {
                    damage = 1;
                    crit = false;
                }
                shown[hit] = damage;
                crits[hit] = crit;
                // applyAttack decodes this legacy crit encoding back to the raw value.
                encoded.add(crit ? -Integer.MAX_VALUE + damage : damage);
            }
            // applyAttack performs aggro, immunity and on-hit effects even for zero.
            // Keep the visual target, but never send an all-miss target through it.
            boolean anyDamage = false;
            for (int damage : shown) {
                anyDamage |= damage > 0;
            }
            if (anyDamage) {
                attack.allDamage.put(mob.getObjectId(), encoded);
            }
            replicated.add(new CombatPackets.TargetResult(mob.getObjectId(), shown, crits));
        }
        attack.numAttacked = targets.size();
        attack.numDamage = bulletCount;
        attack.numAttackedAndDamage = (attack.numAttacked << 4) | attack.numDamage;

        // 4. Resource consumption and side effects (mirrors RangedAttackHandler)
        if (ammo != null && !freeAmmo) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, ammo.getPosition(), bulletConsume, false, true);
        }
        if (effect != null && effect.getCooldown() > 0) {
            c.announce(MaplePacketCreator.skillCooldown(skillId, effect.getCooldown()));
            chr.addCooldown(skillId, currentServerTime(), effect.getCooldown() * 1000);
        }
        int visProjectile = (ammo != null && !soulArrow) ? visibleProjectile(chr, ammo.getItemId()) : 0;

        if (GameConstants.isDojo(map.getId()) && attack.numAttacked > 0) {
            chr.setDojoEnergy(chr.getDojoEnergy() + YamlConfig.config.server.DOJO_ENERGY_ATK);
            c.announce(MaplePacketCreator.getEnergy("energy", chr.getDojoEnergy()));
        }
        cancelWindWalkOnAttack(chr, attack.numAttacked);

        // 5. Replicate before mutating: applyAttack emits kill/drop packets, and clients
        // must register the incoming projectile before the death so it waits for impact.
        map.broadcastMessage(CombatPackets.attackResult(requestId, chr.getId(), skillId, skillLevel,
                attack.stance, toleft, attack.speed, attack.display, visProjectile,
                bulletCount, replicated, AttackStatus.Ok));

        // 6. State mutation through the existing combat path (HP, death once, drops, EXP).
        applyAttack(attack, chr, bulletCount);
    }

    private static double calculateAccuracy(MapleCharacter chr, MapleWeaponType weapon) {
        // Total DEX/LUK already include equipment and Maple Warrior.
        int dex = Math.min(999, chr.getTotalDex());
        int luk = Math.min(999, chr.getTotalLuk());
        int job = chr.getJob().getId();
        boolean brawler = (job >= 510 && job <= 512) || (job >= 1510 && job <= 1512);
        boolean dexBased = (job >= 300 && job < 600) || (job >= 1300 && job < 1600);
        double accuracy = brawler ? (dex * 9 + luk * 3) / 10
                : dexBased ? (dex * 6 + luk * 3) / 10 : (dex * 8 + luk * 5) / 10;
        for (Item item : chr.getInventory(MapleInventoryType.EQUIPPED)) {
            if (item instanceof Equip) {
                accuracy += ((Equip) item).getAcc();
            }
        }
        Integer buff = chr.getBuffedValue(MapleBuffStat.ACC);
        if (buff != null) {
            accuracy += buff;
        }
        int nimble = chr.getJob().isA(MapleJob.NIGHTWALKER1) ? 14000000 : 4000000;
        if (GameConstants.isInJobTree(nimble, chr.getJob().getId())) {
            accuracy += passiveX(chr, nimble);
        }
        int mastery = 0;
        if (weapon == MapleWeaponType.CLAW) {
            mastery = chr.getJob().isA(MapleJob.NIGHTWALKER1) ? NightWalker.CLAW_MASTERY : Assassin.CLAW_MASTERY;
        } else if (weapon == MapleWeaponType.BOW) {
            mastery = chr.getJob().isA(MapleJob.WINDARCHER1) ? WindArcher.BOW_MASTERY : Hunter.BOW_MASTERY;
        } else if (weapon == MapleWeaponType.CROSSBOW) {
            mastery = Crossbowman.CROSSBOW_MASTERY;
        } else if (weapon == MapleWeaponType.GUN) {
            mastery = Gunslinger.GUN_MASTERY;
        }
        if (mastery != 0 && GameConstants.isInJobTree(mastery, chr.getJob().getId())) {
            accuracy += passiveX(chr, mastery);
        }
        int blessingId = 10000000 * chr.getJobType() + 12;
        Skill blessing = SkillFactory.getSkill(blessingId);
        int blessingLevel = chr.getSkillLevel(blessingId);
        if (blessing != null && blessingLevel > 0 && blessingLevel <= blessing.getMaxLevel()) {
            accuracy += blessing.getEffect(blessingLevel).getZ();
        }
        return Math.max(0, Math.min(999, accuracy));
    }

    private static int passiveX(MapleCharacter chr, int id) {
        Skill skill = SkillFactory.getSkill(id);
        int level = chr.getSkillLevel(id);
        return skill != null && level > 0 && level <= skill.getMaxLevel() ? skill.getEffect(level).getX() : 0;
    }

    private static double hitChance(double accuracy, int playerLevel, int mobLevel, int evasion) {
        if (evasion <= 0) {
            return 1.0;
        }
        double levelDifference = Math.max(0.0, (double) mobLevel - playerLevel);
        double denominator = (1.84 + 0.07 * levelDifference) * evasion;
        return Math.max(0.0, Math.min(1.0, Math.max(0.0, accuracy) / denominator - 1.0));
    }

    private static int namedShotCount(int skillId) {
        switch (skillId) {
            case 4001344: case 14001004: // Lucky Seven
            case 3001005: case 13001003: // Archer / Wind Archer Double Shot
                return 2;
            case 4121007: case 14111005: // Night Lord / Night Walker Triple Throw
                return 3;
            case 3111006: case 3211006: // Ranger / Sniper Strafe
                return 4;
            default:
                return 0;
        }
    }

    private static boolean isSkillWeapon(int skillId, MapleWeaponType type) {
        switch (skillId) {
            case 4001344: case 14001004: case 4121007: case 14111005:
                return type == MapleWeaponType.CLAW;
            case 3111006:
                return type == MapleWeaponType.BOW;
            case 3211006:
                return type == MapleWeaponType.CROSSBOW;
            case 3001005:
                return type == MapleWeaponType.BOW || type == MapleWeaponType.CROSSBOW;
            case 13001003:
                return type == MapleWeaponType.BOW;
            default:
                return false;
        }
    }

    private static double[] calculateSkillDamageBounds(MapleCharacter chr, MapleWeaponType type,
                                                       int skillId, MapleStatEffect effect) {
        double watk = chr.getTotalWatk() / 100.0;
        double lo, hi;
        if (skillId == 4001344 || skillId == 14001004 || skillId == 4121007 || skillId == 14111005) {
            // Actual server roll, not parseDamage's anti-cheat ceiling. Mastery
            // and STR/DEX must not change these LUK-only throwing-skill bounds.
            lo = 2.5 * chr.getTotalLuk() * watk;
            hi = 5.0 * chr.getTotalLuk() * watk;
        } else {
            double primary = type.getMaxDamageMultiplier() * chr.getTotalDex();
            double secondary = chr.getTotalStr();
            lo = (primary * 0.9 * getSkillMastery(chr, type) + secondary) * watk;
            hi = (primary + secondary) * watk;
        }
        double scale = effect.getDamage() / 100.0;
        int damageBuff = 100;
        for (PlayerBuffValueHolder buff : chr.getAllBuffs()) {
            damageBuff += buff.effect.getDamage() - 100;
        }
        scale *= Math.max(0, damageBuff) / 100.0;
        return new double[]{lo * scale, hi * scale};
    }

    private static double getSkillMastery(MapleCharacter chr, MapleWeaponType type) {
        int[] ids = type == MapleWeaponType.BOW ? new int[]{Hunter.BOW_MASTERY, WindArcher.BOW_MASTERY, 3120005}
                : new int[]{Crossbowman.CROSSBOW_MASTERY, 3220004};
        int mastery = 0;
        for (int id : ids) {
            int level = chr.getSkillLevel(id);
            if (level > 0) {
                mastery = Math.max(mastery, SkillFactory.getSkill(id).getEffect(level).getMastery());
            }
        }
        // WZ mastery 10 -> 60%, mastery 16 -> 90%; no learned mastery -> 10%.
        return Math.min(0.9, 0.1 + 0.05 * mastery);
    }

    private static double skillCriticalMultiplier(MapleCharacter chr, MapleStatEffect effect) {
        int bonus = 0;
        int[] ids = {Assassin.CRITICAL_THROW, NightWalker.CRITICAL_THROW, Archer.CRITICAL_SHOT, WindArcher.CRITICAL_SHOT};
        for (int id : ids) {
            int level = chr.getSkillLevel(id);
            if (level > 0) {
                bonus = Math.max(bonus, SkillFactory.getSkill(id).getEffect(level).getDamage() - 100);
            }
        }
        MapleStatEffect eyes = chr.getBuffEffect(MapleBuffStat.SHARP_EYES);
        if (eyes != null) {
            bonus += eyes.getY() - 100;
        }
        return 1.0 + Math.max(0, bonus) / (double) Math.max(1, effect.getDamage());
    }

    private static boolean rollSkillCrit(MapleCharacter chr) {
        double chance = 0;
        int[] ids = {Assassin.CRITICAL_THROW, NightWalker.CRITICAL_THROW, Archer.CRITICAL_SHOT, WindArcher.CRITICAL_SHOT};
        for (int id : ids) {
            int level = chr.getSkillLevel(id);
            if (level > 0) {
                chance = Math.max(chance, SkillFactory.getSkill(id).getEffect(level).getProp());
            }
        }
        MapleStatEffect eyes = chr.getBuffEffect(MapleBuffStat.SHARP_EYES);
        if (eyes != null) {
            chance += eyes.getX() / 100.0;
        }
        return Randomizer.nextDouble() < Math.min(1, chance);
    }

    private static int admitRequest(MapleCharacter chr, long requestId) {
        RequestState state = requestStates.computeIfAbsent(chr.getId(), k -> new RequestState());
        synchronized (state) {
            if (requestId <= state.lastRequestId) {
                return AttackStatus.Duplicate;
            }
            long now = System.currentTimeMillis();
            if (state.lastRequestId >= 0 && now - state.lastAcceptedAt < MIN_ATTACK_INTERVAL_MS) {
                state.lastRequestId = requestId;
                return AttackStatus.TooFast;
            }
            state.lastRequestId = requestId;
            state.lastAcceptedAt = now;
            return AttackStatus.Ok;
        }
    }

    private static void reject(MapleClient c, MapleCharacter chr, long requestId, int skillId, int status) {
        c.announce(CombatPackets.rejected(requestId, chr.getId(), skillId, status));
        c.announce(MaplePacketCreator.enableActions());
    }

    private static boolean isRangedWeapon(MapleWeaponType type, int weaponId) {
        return type == MapleWeaponType.CLAW || type == MapleWeaponType.BOW
                || type == MapleWeaponType.CROSSBOW || type == MapleWeaponType.GUN
                || weaponId == 1472063;
    }

    private static Item findAmmo(MapleCharacter chr, Item weapon, MapleWeaponType type, int requiredQuantity) {
        MapleInventory inv = chr.getInventory(MapleInventoryType.USE);
        for (short i = 1; i <= inv.getSlotLimit(); i++) {
            Item item = inv.getItem(i);
            if (item == null || item.getQuantity() < requiredQuantity) {
                continue;
            }
            int id = item.getItemId();
            boolean bow = ItemConstants.isArrowForBow(id);
            boolean cbow = ItemConstants.isArrowForCrossBow(id);
            if (type == MapleWeaponType.CLAW && ItemConstants.isThrowingStar(id) && weapon.getItemId() != 1472063) {
                if (((id == 2070007 || id == 2070018) && chr.getLevel() < 70) || (id == 2070016 && chr.getLevel() < 50)) {
                    continue;
                }
                return item;
            } else if (type == MapleWeaponType.GUN && ItemConstants.isBullet(id)) {
                if (chr.getLevel() > (id % 10) * 20 + 9) {
                    return item;
                }
            } else if ((type == MapleWeaponType.BOW && bow) || (type == MapleWeaponType.CROSSBOW && cbow)
                    || (weapon.getItemId() == 1472063 && (bow || cbow))) {
                return item;
            }
        }
        return null;
    }

    private static int visibleProjectile(MapleCharacter chr, int projectile) {
        if (ItemConstants.isThrowingStar(projectile)) {
            MapleInventory cash = chr.getInventory(MapleInventoryType.CASH);
            for (short i = 1; i <= cash.getSlotLimit(); i++) {
                Item item = cash.getItem(i);
                if (item != null && item.getItemId() / 1000 == 5021) {
                    return item.getItemId();
                }
            }
        }
        return projectile;
    }

    private static List<MapleMonster> selectTargets(MapleCharacter chr, MapleMap map, AttackRequest request,
                                                    boolean toleft, int maxTargets) {
        Point origin = chr.getPosition();
        Set<Integer> seen = new LinkedHashSet<>();
        List<MapleMonster> targets = new ArrayList<>();
        int count = request.targetOidsLength();
        for (int i = 0; i < count && targets.size() < maxTargets; i++) {
            int oid = request.targetOids(i);
            if (!seen.add(oid)) {
                continue;
            }
            MapleMonster mob = map.getMonsterByOid(oid);
            if (mob == null || !mob.isAlive()) {
                continue;
            }
            Point mp = mob.getPosition();
            int dx = mp.x - origin.x;
            if (origin.distanceSq(mp) > MAX_RANGE_SQ || Math.abs(mp.y - origin.y) > MAX_VERTICAL_RANGE) {
                continue;
            }
            if (toleft ? dx > FACING_SLACK : dx < -FACING_SLACK) {
                continue;
            }
            targets.add(mob);
        }
        return targets;
    }

    // Same chain as AbstractDealDamageHandler.parseDamage for a skill-less ranged attack.
    private static long calculateBasicMaxDamage(MapleCharacter chr) {
        long max = chr.calculateMaxBaseDamage(chr.getTotalWatk());

        if (chr.getEnergyBar() == 15000) {
            int energycharge = chr.isCygnus() ? ThunderBreaker.ENERGY_CHARGE : Marauder.ENERGY_CHARGE;
            MapleStatEffect ceffect = SkillFactory.getSkill(energycharge).getEffect(chr.getSkillLevel(energycharge));
            max *= (100 + ceffect.getDamage()) / 100;
        }

        int bonusDmgBuff = 100;
        for (PlayerBuffValueHolder pbvh : chr.getAllBuffs()) {
            bonusDmgBuff += pbvh.effect.getDamage() - 100;
        }
        if (bonusDmgBuff != 100) {
            max = (long) Math.ceil(max * (bonusDmgBuff / 100.0f));
        }

        if (chr.getMapId() >= 914000000 && chr.getMapId() <= 914000500) {
            max += 80000;
        }
        if (chr.getBuffEffect(MapleBuffStat.SHARP_EYES) != null) {
            max *= 1.4;
        }
        return Math.max(1, max);
    }

    private static boolean canCrit(MapleCharacter chr) {
        MapleJob job = chr.getJob();
        return job.isA(MapleJob.BOWMAN) || job.isA(MapleJob.THIEF) || job.isA(MapleJob.NIGHTWALKER1)
                || job.isA(MapleJob.WINDARCHER1) || job == MapleJob.ARAN3 || job == MapleJob.ARAN4
                || job == MapleJob.MARAUDER || job == MapleJob.BUCCANEER
                || chr.getBuffEffect(MapleBuffStat.SHARP_EYES) != null;
    }

    private static boolean rollCrit(MapleCharacter chr) {
        int[] critSkills = {Assassin.CRITICAL_THROW, NightWalker.CRITICAL_THROW, Archer.CRITICAL_SHOT, WindArcher.CRITICAL_SHOT};
        for (int id : critSkills) {
            int lvl = chr.getSkillLevel(id);
            if (lvl > 0) {
                return SkillFactory.getSkill(id).getEffect(lvl).makeChanceResult();
            }
        }
        // No crit skill learned: Sharp Eyes alone grants an assumed 15% chance.
        return chr.getBuffEffect(MapleBuffStat.SHARP_EYES) != null && Randomizer.nextInt(100) < 15;
    }

    private static double getMastery(MapleCharacter chr, MapleWeaponType type) {
        int[] ids;
        if (type == MapleWeaponType.CLAW) {
            ids = new int[]{Assassin.CLAW_MASTERY, NightWalker.CLAW_MASTERY};
        } else if (type == MapleWeaponType.BOW) {
            ids = new int[]{Hunter.BOW_MASTERY, WindArcher.BOW_MASTERY};
        } else if (type == MapleWeaponType.CROSSBOW) {
            ids = new int[]{Crossbowman.CROSSBOW_MASTERY};
        } else if (type == MapleWeaponType.GUN) {
            ids = new int[]{Gunslinger.GUN_MASTERY};
        } else {
            ids = new int[0];
        }
        int best = 0;
        for (int id : ids) {
            best = Math.max(best, chr.getSkillLevel(id));
        }
        return Math.min(0.9, 0.1 + 0.05 * best);
    }

    private static int rollDamage(MapleCharacter chr, MapleMonster mob, long max, double mastery, boolean crit) {
        return rollDamageBounds(chr, mob, max * 0.9 * mastery, max, crit ? 1.5 : 1.0);
    }

    private static int rollDamageBounds(MapleCharacter chr, MapleMonster mob, double lo, double hi, double criticalMultiplier) {
        int levelDiff = Math.max(0, mob.getLevel() - chr.getLevel());
        Integer defenseModifier = mob.getStatusValue(MonsterStatus.WDEF);
        int pdd = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                (long) mob.getStats().getPDDamage() + (defenseModifier == null ? 0 : defenseModifier)));
        lo = lo * (1 - 0.01 * levelDiff) - pdd * 0.6;
        hi = hi * (1 - 0.01 * levelDiff) - pdd * 0.5;
        lo = Math.max(1, lo);
        hi = Math.max(1, hi);
        if (lo > hi) {
            lo = hi;
        }

        double damage = lo + Randomizer.nextDouble() * (hi - lo);
        damage *= criticalMultiplier;
        return (int) Math.max(1, Math.min(MAX_DAMAGE, Math.round(damage)));
    }

    private static void cancelWindWalkOnAttack(MapleCharacter chr, int numAttacked) {
        if (numAttacked <= 0) {
            return;
        }
        if (chr.getSkillLevel(SkillFactory.getSkill(WindArcher.WIND_WALK)) > 0 && chr.getBuffedValue(MapleBuffStat.WIND_WALK) != null) {
            chr.cancelEffectFromBuffStat(MapleBuffStat.WIND_WALK);
            chr.cancelBuffStats(MapleBuffStat.WIND_WALK);
        }
    }
}
