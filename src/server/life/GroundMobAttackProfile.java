package server.life;

import java.awt.Point;
import provider.MapleData;
import provider.MapleDataTool;
import provider.wz.MapleDataType;

/** Experimental WZ-backed single physical projectile attack profile. */
public record GroundMobAttackProfile(int range, int offsetX, int offsetY, int physicalAttack,
                                     int accuracy, int windupMs, int animationMs, int recoveryMs) {
    // Per-template presentation choice; authority is separately allowlisted.
    // This artistic choice is NOT inferred from attackAfter.
    public static int releaseFrameFor(int id) {
        return id == 3210800 ? 5 : -1;
    }

    /**
     * Checks the experimental subset. Parsing a profile alone does not enable
     * server authority; templates also require explicit movement opt-in.
     */
    public static GroundMobAttackProfile fromWz(MapleData mob, int releaseFrame) {
        if (mob == null || releaseFrame < 1 || mob.getChildByPath("info/link") != null
                || mob.getChildByPath("attack2") != null) return null;
        for (String unsupported : new String[]{"skill", "revive", "selfDestruction",
                "loseItem", "ban", "removeAfter", "fixedDamage"}) {
            if (mob.getChildByPath("info/" + unsupported) != null) return null;
        }
        for (MapleData child : mob) {
            if (child.getName().matches("attack[0-9]+") && !child.getName().equals("attack1"))
                return null;
        }
        MapleData attack = mob.getChildByPath("attack1");
        MapleData info = mob.getChildByPath("attack1/info");
        MapleData rangeNode = mob.getChildByPath("attack1/info/range");
        MapleData hitNode = mob.getChildByPath("attack1/info/hit");
        MapleData sp = mob.getChildByPath("attack1/info/range/sp");
        MapleData hit = mob.getChildByPath("attack1/info/hit/0");
        if (attack == null || info == null || rangeNode == null || hitNode == null
                || sp == null || sp.getType() != MapleDataType.VECTOR || hit == null
                || hit.getType() != MapleDataType.CANVAS
                || MapleDataTool.getIntConvert("type", info, -1) != 1) return null;
        for (MapleData child : rangeNode) {
            if (!child.getName().equals("sp") && !child.getName().equals("r")) return null;
        }
        int hitFrames = hitNode.getChildren().size();
        if (hitFrames < 1 || hitFrames > 32) return null;
        for (int i = 0; i < hitFrames; ++i) {
            MapleData frame = hitNode.getChildByPath(Integer.toString(i));
            if (frame == null || frame.getType() != MapleDataType.CANVAS) return null;
        }
        // Unknown combat effects are not silently treated as a plain attack.
        for (MapleData child : info) {
            String name = child.getName();
            if (!name.equals("range") && !name.equals("hit") && !name.equals("type")
                    && !name.equals("attackAfter") && !name.equals("PADamage")) return null;
        }
        int range = MapleDataTool.getIntConvert("range/r", info, 0);
        int physical = MapleDataTool.getIntConvert("PADamage", info, -1);
        int accuracy = MapleDataTool.getIntConvert("info/acc", mob, -1);
        int recovery = MapleDataTool.getIntConvert("attackAfter", info, -1);
        Point offset = MapleDataTool.getPoint(sp);
        if (offset == null || Math.abs((long) offset.x) > 1000 || Math.abs((long) offset.y) > 1000) return null;
        int total = 0, windup = 0, frames = 0;
        for (int i = 0; ; ++i) {
            MapleData frame = attack.getChildByPath(Integer.toString(i));
            if (frame == null) break;
            if (frame.getType() != MapleDataType.CANVAS) return null;
            int delay = MapleDataTool.getIntConvert("delay", frame, 100);
            if (delay <= 0 || delay > 10000 || total > 10000 - delay) return null;
            if (i < releaseFrame) windup += delay;
            total += delay;
            ++frames;
            if (frames > 100) return null;
        }
        // No skipped frames or unmodelled direct attack actions.
        if (attack.getChildren().size() != frames + 1) return null;
        if (range <= 0 || range > 1000 || physical < 0 || accuracy < 0 || recovery < 0
                || releaseFrame >= frames || total > 10000 || recovery > 10000) return null;
        return new GroundMobAttackProfile(range, offset.x, offset.y, physical, accuracy,
                windup, total, recovery);
    }
}
