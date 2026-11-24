package server.life;

import client.MapleBuffStat;
import client.MapleCharacter;
import client.MapleJob;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import tools.Randomizer;

/** Server-side approximation of this client's physical touch roll. */
public final class GroundMobAttackDamage {
    private GroundMobAttackDamage() {}

    public static double hitChance(int accuracy, int mobLevel, int playerLevel, int avoid) {
        if (avoid <= 0) return 1.0;
        int levelDelta = Math.max(0, playerLevel - mobLevel);
        return Math.max(0.01, Math.min(1.0,
                Math.max(0, accuracy) / ((1.84 + 0.07 * levelDelta) * avoid + 1.0)));
    }

    public static int physicalDamage(int attack, int defense) {
        return defense <= 0 ? attack : attack / 2 + attack / defense;
    }

    public static int roll(MapleCharacter chr, MapleMonster mob, GroundMobAttackProfile profile) {
        int defense = 0, avoid = 0;
        MapleInventory equipped = chr.getInventory(MapleInventoryType.EQUIPPED);
        equipped.lockInventory();
        try {
            for (Item item : equipped) {
                if (item instanceof Equip equip) {
                    defense += equip.getWdef();
                    avoid += equip.getAvoid();
                }
            }
        } finally {
            equipped.unlockInventory();
        }
        Integer defenseBuff = chr.getBuffedValue(MapleBuffStat.WDEF);
        Integer avoidBuff = chr.getBuffedValue(MapleBuffStat.AVOID);
        defense += defenseBuff == null ? 0 : defenseBuff;
        avoid += avoidBuff == null ? 0 : avoidBuff;
        int dex = chr.getTotalDex(), luk = chr.getTotalLuk();
        MapleJob job = chr.getJob();
        if (job.isA(MapleJob.BRAWLER) || job.isA(MapleJob.THUNDERBREAKER2)) {
            avoid += (dex * 6 + luk * 2) / 4;
        } else if (job.isA(MapleJob.GUNSLINGER)) {
            avoid += (dex + luk * 4) / 8;
        } else {
            avoid += (dex + luk * 2) / 4;
        }
        Integer accStatus = mob.getStatusValue(client.status.MonsterStatus.ACC);
        int accuracy = profile.accuracy() + (accStatus == null ? 0 : accStatus);
        if (Randomizer.nextDouble() >= hitChance(accuracy, mob.getLevel(), chr.getLevel(), avoid)) return 0;
        Integer watkStatus = mob.getStatusValue(client.status.MonsterStatus.WATK);
        int attack = Math.max(0, profile.physicalAttack() + (watkStatus == null ? 0 : watkStatus));
        int min = attack * 80 / 100;
        int roll = attack <= min ? min : min + Randomizer.nextInt(attack - min);
        return physicalDamage(roll, Math.max(0, defense));
    }
}
