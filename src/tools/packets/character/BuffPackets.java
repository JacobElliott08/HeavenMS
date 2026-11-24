package tools.packets.character;

import java.util.List;

import com.google.flatbuffers.FlatBufferBuilder;

import client.MapleBuffStat;
import protocol.generated.maple.character.BuffApply;
import protocol.generated.maple.character.BuffCancel;
import protocol.generated.maple.character.BuffStat;
import protocol.generated.maple.character.BuffStatValue;
import protocol.generated.maple.character.ForeignBuffApply;
import protocol.generated.maple.character.ForeignBuffCancel;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import tools.Pair;

/**
 * FlatBuffers replacements for the legacy GIVE_BUFF / CANCEL_BUFF and
 * GIVE_FOREIGN_BUFF / CANCEL_FOREIGN_BUFF packets.
 *
 * Buffs are keyed by SOURCE id (positive skill id, negative item id, i.e.
 * MapleStatEffect.getBuffSourceId()). Re-sending a source refreshes/replaces
 * that source's contribution on the client; cancelling a source removes only
 * that source's contribution.
 */
public final class BuffPackets {
    private BuffPackets() {
    }

    /**
     * Maps a server MapleBuffStat onto the wire BuffStat enum (which mirrors
     * the client's Buffstat::Id ordering). Returns -1 for stats the client
     * does not model; those statups are simply omitted from the message.
     */
    public static int toWireStat(MapleBuffStat stat) {
        switch (stat) {
            case MORPH: return BuffStat.Morph;
            case RECOVERY: return BuffStat.Recovery;
            case MAPLE_WARRIOR: return BuffStat.MapleWarrior;
            case STANCE: return BuffStat.Stance;
            case SHARP_EYES: return BuffStat.SharpEyes;
            case MANA_REFLECTION: return BuffStat.ManaReflection;
            case SHADOW_CLAW: return BuffStat.ShadowClaw;
            case INFINITY: return BuffStat.Infinity;
            case HOLY_SHIELD: return BuffStat.HolyShield;
            case HAMSTRING: return BuffStat.Hamstring;
            case BLIND: return BuffStat.Blind;
            case CONCENTRATE: return BuffStat.Concentrate;
            case ECHO_OF_HERO: return BuffStat.EchoOfHero;
            case GHOST_MORPH: return BuffStat.GhostMorph;
            case AURA: return BuffStat.Aura;
            case CONFUSE: return BuffStat.Confuse;
            case BERSERK_FURY: return BuffStat.BerserkFury;
            case DIVINE_BODY: return BuffStat.DivineBody;
            case SPARK: return BuffStat.Spark;
            case FINALATTACK: return BuffStat.FinalAttack;
            case WATK: return BuffStat.Watk;
            case WDEF: return BuffStat.Wdef;
            case MATK: return BuffStat.Matk;
            case MDEF: return BuffStat.Mdef;
            case ACC: return BuffStat.Acc;
            case AVOID: return BuffStat.Avoid;
            case HANDS: return BuffStat.Hands;
            case SPEED: return BuffStat.Speed;
            case JUMP: return BuffStat.Jump;
            case MAGIC_GUARD: return BuffStat.MagicGuard;
            case DARKSIGHT: return BuffStat.DarkSight;
            case BOOSTER: return BuffStat.Booster;
            case POWERGUARD: return BuffStat.PowerGuard;
            case HYPERBODYHP: return BuffStat.HyperBodyHp;
            case HYPERBODYMP: return BuffStat.HyperBodyMp;
            case INVINCIBLE: return BuffStat.Invincible;
            case SOULARROW: return BuffStat.SoulArrow;
            case STUN: return BuffStat.Stun;
            case POISON: return BuffStat.Poison;
            case SEAL: return BuffStat.Seal;
            case DARKNESS: return BuffStat.Darkness;
            case COMBO: return BuffStat.Combo;
            case SUMMON: return BuffStat.Summon;
            case WK_CHARGE: return BuffStat.WkCharge;
            case DRAGONBLOOD: return BuffStat.DragonBlood;
            case HOLY_SYMBOL: return BuffStat.HolySymbol;
            case MESOUP: return BuffStat.MesoUp;
            case SHADOWPARTNER: return BuffStat.ShadowPartner;
            case PICKPOCKET: return BuffStat.Pickpocket;
            case PUPPET: return BuffStat.Puppet;
            case MESOGUARD: return BuffStat.MesoGuard;
            case WEAKEN: return BuffStat.Weaken;
            case DASH: return BuffStat.Dash;
            case DASH2: return BuffStat.Dash2;
            case ELEMENTAL_RESET: return BuffStat.ElementalReset;
            case ARAN_COMBO: return BuffStat.AranCombo;
            case COMBO_DRAIN: return BuffStat.ComboDrain;
            case COMBO_BARRIER: return BuffStat.ComboBarrier;
            case BODY_PRESSURE: return BuffStat.BodyPressure;
            case SMART_KNOCKBACK: return BuffStat.SmartKnockback;
            case ENERGY_CHARGE: return BuffStat.EnergyCharge;
            case MONSTER_RIDING: return BuffStat.MonsterRiding;
            case HOMING_BEACON: return BuffStat.HomingBeacon;
            case SPEED_INFUSION: return BuffStat.SpeedInfusion;
            default: return -1;
        }
    }

    private static int buildStatups(FlatBufferBuilder builder, List<Pair<MapleBuffStat, Integer>> statups) {
        int count = 0;
        int[] offsets = new int[statups.size()];
        for (Pair<MapleBuffStat, Integer> statup : statups) {
            int wireStat = toWireStat(statup.getLeft());
            if (wireStat < 0) {
                continue;
            }
            BuffStatValue.startBuffStatValue(builder);
            BuffStatValue.addStat(builder, wireStat);
            BuffStatValue.addValue(builder, statup.getRight());
            offsets[count++] = BuffStatValue.endBuffStatValue(builder);
        }
        int[] used = new int[count];
        System.arraycopy(offsets, 0, used, 0, count);
        return BuffApply.createStatupsVector(builder, used);
    }

    private static byte[] finish(FlatBufferBuilder builder, byte messageType, int messageOffset) {
        Packet.startPacket(builder);
        Packet.addServerMessage(builder, messageOffset);
        Packet.addServerMessageType(builder, messageType);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }

    /** Server->client, local player: apply/refresh one buff source. */
    public static byte[] giveBuff(int sourceId, int durationMs, List<Pair<MapleBuffStat, Integer>> statups) {
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        int statupsOffset = buildStatups(builder, statups);
        int msg = BuffApply.createBuffApply(builder, sourceId, statupsOffset, durationMs);
        return finish(builder, (byte) ServerMessage.protocol_generated_maple_character_BuffApply, msg);
    }

    /** Server->client, local player: remove one buff source's contribution. */
    public static byte[] cancelBuff(int sourceId) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = BuffCancel.createBuffCancel(builder, sourceId);
        return finish(builder, (byte) ServerMessage.protocol_generated_maple_character_BuffCancel, msg);
    }

    /** Server->client: another player's buff source became active. */
    public static byte[] giveForeignBuff(int characterId, int sourceId, int durationMs, List<Pair<MapleBuffStat, Integer>> statups) {
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        int statupsOffset = buildStatups(builder, statups);
        int msg = ForeignBuffApply.createForeignBuffApply(builder, characterId, sourceId, statupsOffset, durationMs);
        return finish(builder, (byte) ServerMessage.protocol_generated_maple_character_ForeignBuffApply, msg);
    }

    /** Server->client: another player's buff source expired or was cancelled. */
    public static byte[] cancelForeignBuff(int characterId, int sourceId) {
        FlatBufferBuilder builder = new FlatBufferBuilder(64);
        int msg = ForeignBuffCancel.createForeignBuffCancel(builder, characterId, sourceId);
        return finish(builder, (byte) ServerMessage.protocol_generated_maple_character_ForeignBuffCancel, msg);
    }
}
