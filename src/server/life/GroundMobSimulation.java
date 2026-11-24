package server.life;

import java.awt.Point;
import server.maps.MapleFoothold;
import server.maps.MapleFootholdTree;
import tools.Randomizer;

/**
 * First grounded-only slice. No client movement fragments or client coordinates.
 * Walks along connected non-wall footholds; turns at ledges/disconnected joins.
 * The owner holds monsterLock when advancing or applying accepted hits.
 */
public final class GroundMobSimulation {
    public static final int INTERVAL_MS = 100;
    // Constants::TIMESTEP, Mob::update(JUMP), Physics::move_normal.
    // Units are pixels per legacy update, NOT pixels/second.
    private static final int PHYSICS_STEP_MS = 8;
    private static final double JUMP_FORCE = -5.0, GRAVITY = 0.14;
    private final MapleFootholdTree tree;
    private final double speed;
    private MapleFoothold foothold;
    private double x, y, horizontalSpeed, verticalSpeed;
    private final float walkForce;
    private int direction, decisionTicks, hitTicks, physicsRemainder;
    private boolean jumping, launch;
    private final boolean canJump;
    private boolean walking = true;
    private double knockSpeed;

    private GroundMobSimulation(MapleFootholdTree tree, MapleFoothold foothold,
                                Point pos, int wzSpeed, boolean left, boolean canJump) {
        this.tree = tree;
        this.foothold = foothold;
        x = pos.x;
        y = groundY(foothold, x);
        speed = Math.max(5, Math.min(100, 100 + wzSpeed));
        // Mob stores this as float and multiplies by the float literal 0.001f.
        walkForce = (wzSpeed + 100) * 0.001f;
        direction = left ? -1 : 1;
        this.canJump = canJump;
        decisionTicks = 20;
    }

    public static GroundMobSimulation create(int id, MapleMonsterStats stats,
                                             MapleFootholdTree tree, int fh, Point pos, boolean left) {
        boolean canJump = hasJumpMovement(id);
        if (!eligible(id, stats, tree)) return null;
        MapleFoothold ground = tree.getById(fh);
        if (ground == null || ground.isWall() || pos.x < ground.getX1()
                || pos.x > ground.getX2() || Math.abs(groundY(ground, pos.x) - pos.y) > 8) return null;
        return new GroundMobSimulation(tree, ground, pos, stats.groundSpeed, left, canJump);
    }

    /** Attach only: preserve the old valid-FH behavior, then recover a stale FH
     * only at a unique surface or connected endpoint within the spawn-rebase
     * rounding margin. This does not change the legacy placement query.
     */
    public static GroundMobSimulation createAtAttachment(int id, MapleMonsterStats stats,
                                                          MapleFootholdTree tree, int fh,
                                                          Point pos, boolean left) {
        if (!eligible(id, stats, tree)) return null;
        GroundMobSimulation simulation = create(id, stats, tree, fh, pos, left);
        if (simulation != null) return simulation;
        MapleFoothold ground = tree.findGroundForAttachment(pos, 2.0);
        if (ground == null) return null;
        return create(id, stats, tree, ground.getId(), pos, left);
    }

    private static boolean eligible(int id, MapleMonsterStats stats, MapleFootholdTree tree) {
        boolean canJump = hasJumpMovement(id);
        return supportsGroundMovement(id) && !stats.isBoss()
                && (canJump ? stats.groundJumpOnly : stats.groundWalkOnly)
                // Only individually opted-in attacks with a validated WZ profile
                // may use the projectile simulator. Other attacks stay legacy.
                && (id == 3210800
                    ? stats.groundHasAttack && stats.groundAttackProfile != null
                    : !stats.groundHasAttack)
                && stats.getSkills().isEmpty()
                && stats.getRevives().isEmpty() && tree != null;
    }

    private static boolean supportsGroundMovement(int id) {
        switch (id) {
            case 100100: // Snail
            case 100101: // Blue Snail
            case 120100: // Shroom
            case 100120: // Tino
            case 100121: // Tiv
            case 1110101: // Dark Stump
            case 1120100: // Octopus
            case 1130100: // Axe Stump
            case 2110200: // Horny Mushroom
            case 130101: // Red Snail in the bundled v83-style WZ data
            case 210100: // Slime (the green slime)
            case 1110100: // Green Mushroom
            case 1210100: // Pig
            case 1210101: // Ribbon Pig
            case 1210102: // Orange Mushroom
            case 2220100: // Blue Mushroom
            case 2230101: // Zombie Mushroom
            case 1210103: // Bubbling
            case 3210800: // Lupin: separately validated projectile attack
            // Broad 100-105 ordinary-ground batch. These remain subject to
            // non-boss/non-skill/non-flying/valid-foothold guards in create().
            case 130100: // Stump
            case 1110130: // Dejected Green Mushroom
            case 1140100: // Ghost Stump
            case 1140130: // Smirking Ghost Stump
            case 2130100: // Dark Axe Stump
            case 2130103: // Jr. Necki
            case 2230110: // Wooden Mask
            case 2230111: // Rocky Mask
            case 4130100: // Copper Drake
            case 5130100: // Drake
            case 2230100: // Evil Eye
            case 2230102: // Wild Boar
            case 2230131: // Annoyed Zombie Mushroom
            case 3230100: // Curse Eye
            case 3230101: // Jr. Wraith
            case 4230100: // Cold Eye
            case 4230102: // Wraith
            case 4230125: // Skeledog
            case 4230126: // Mummydog
            case 4230400: // Iron Boar
            case 6230100: // Wild Kargo
            // Individually screened ordinary templates in the same map scan.
            case 4300006:
            case 4300009:
            case 9300000:
            case 9300001:
            case 9400616:
            case 9400618:
            case 9400620:
            case 9400621:
            case 9400657:
            case 9300002:
            case 9300274:
            case 9400614:
            case 9400615:
            case 9400617:
            case 9400619:
            case 9400622:
            case 9400655:
            case 9400656:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasJumpMovement(int id) {
        // These bundled Mob.wz templates have a jump action in addition to move.
        switch (id) {
            case 210100:
            case 1210100:
            case 1210101:
            case 1210102:
            case 2220100:
            case 2230101:
            case 1210103:
            case 3210800:
            case 2230100:
            case 2230102:
            case 2230131:
            case 3230100:
            case 3230101:
            case 4230100:
            case 4230102:
            case 4230125:
            case 4230126:
            case 4230400:
            case 6230100:
            case 9300002:
            case 9300274:
            case 9400614:
            case 9400615:
            case 9400617:
            case 9400619:
            case 9400622:
            case 9400655:
            case 9400656:
                return true;
            default:
                return false;
        }
    }

    // Do not use MapleFoothold.calculateFooting: its integer slope loses real WZ slopes.
    private static double groundY(MapleFoothold f, double x) {
        return f.getY1() + (x - f.getX1()) * (f.getY2() - f.getY1())
                / (double) (f.getX2() - f.getX1());
    }

    public void hit(int attackerX) {
        direction = attackerX <= x ? 1 : -1;
        knockSpeed = direction * 120.0;
        hitTicks = 4;
    }

    public boolean isGrounded() { return !jumping; }
    public boolean isInHitReaction() { return hitTicks > 0; }

    /** Attack-capable ground mobs: pursuit and committed attack hold. */
    public void directAttackTarget(Integer targetX, boolean hold, boolean faceLeft) {
        if (hitTicks > 0) return;
        if (hold && !jumping) {
            walking = false;
            direction = faceLeft ? -1 : 1;
            horizontalSpeed = 0;
            decisionTicks = 2;
        } else if (targetX != null) {
            direction = targetX < x ? -1 : 1;
            walking = true;
            decisionTicks = Math.max(decisionTicks, 2);
        }
    }

    public void tick(boolean stopped, int speedPercent) {
        if (--decisionTicks <= 0 && hitTicks == 0 && !jumping) {
            walking = Randomizer.nextInt(4) != 0;
            direction = Randomizer.nextBoolean() ? 1 : -1;
            decisionTicks = 15 + Randomizer.nextInt(16);
            // Mirror the client's occasional jump decision while retaining the
            // server's own position and foothold authority.
            if (canJump && walking && !stopped && Randomizer.nextInt(4) == 0) {
                jumping = launch = true;
            }
        }
        double velocity = stopped ? 0 : hitTicks > 0 ? knockSpeed
                : walking ? direction * speed * Math.max(0, 100 + speedPercent) / 100.0 : 0;
        if (canJump) {
            physicsRemainder += INTERVAL_MS;
            while (physicsRemainder >= PHYSICS_STEP_MS) {
                physicsRemainder -= PHYSICS_STEP_MS;
                if (!stopped) stepJumpPhysics(velocity, speedPercent);
            }
        } else {
            moveHorizontal(velocity * INTERVAL_MS / 1000.0);
            y = groundY(foothold, x);
        }
        if (hitTicks > 0) {
            --hitTicks;
            knockSpeed *= 0.5;
        }
    }

    private void stepJumpPhysics(double hitVelocity, int speedPercent) {
        if (!jumping || launch) {
            // Ground friction: inertia = hspeed / 3; slope is clamped to +/-0.5.
            double force = jumping || !walking ? 0
                    : direction * walkForce * Math.max(0, 100 + speedPercent) / 100.0;
            double slope = Math.max(-0.5, Math.min(0.5,
                    (foothold.getY2() - foothold.getY1())
                    / (double) (foothold.getX2() - foothold.getX1())));
            if (force == 0 && Math.abs(horizontalSpeed) < 0.1) {
                horizontalSpeed = 0;
            } else {
                double inertia = horizontalSpeed / 3.0;
                horizontalSpeed += force - (0.5 + 0.1 * (1.0 - slope * inertia)) * inertia;
            }
            if (launch) {
                verticalSpeed = JUMP_FORCE;
                launch = false; // No gravity on the grounded launch update.
            }
        } else {
            verticalSpeed += GRAVITY; // Semi-implicit Euler: velocity BEFORE position.
        }
        // Keep the existing accepted-hit knockback contract, not a new damage path.
        if (hitTicks > 0) horizontalSpeed = hitVelocity * PHYSICS_STEP_MS / 1000.0;
        double oldX = x;
        double oldY = y;
        moveHorizontal(horizontalSpeed);
        if (!jumping) {
            y = groundY(foothold, x);
            return;
        }
        double nextY = oldY + verticalSpeed;
        // Like update_fh, choose the nearest surface BELOW the current feet, at
        // the column of the NEW x. Selecting it at the old column can skip the
        // landing on the step where x crosses a foothold boundary, dropping the
        // mob's feet below the floor and letting it fall (and overflow) forever.
        MapleFoothold below = tree.findBelow(x, oldY);
        if (below == null)
            below = foothold; // The walk chain foothold always spans x.
        double ground = groundY(below, x);
        // Like limit_movement, land only when this update crosses that surface.
        // Falling with feet already below it is a tunneled state: recover by landing.
        if (nextY >= ground || (verticalSpeed > 0 && oldY > ground)) {
            foothold = below;
            y = ground;
            verticalSpeed = 0;
            jumping = false;
        } else {
            y = nextY; // Never rebase the arc on a changing slope or platform.
        }
    }

    private void moveHorizontal(double displacement) {
        double target = x + displacement;
        for (int joins = 0; joins < 8; joins++) {
            boolean left = target < foothold.getX1(), right = target > foothold.getX2();
            if (!left && !right) break;
            int edge = left ? foothold.getX1() : foothold.getX2();
            MapleFoothold next = tree.getById(left ? foothold.getPrev() : foothold.getNext());
            boolean continuous = next != null && !next.isWall()
                    && (left ? next.getX2() == edge && next.getY2() == foothold.getY1()
                             : next.getX1() == edge && next.getY1() == foothold.getY2());
            if (!continuous) {
                target = edge;
                direction = left ? 1 : -1;
                knockSpeed = 0;
                horizontalSpeed = 0;
                break;
            }
            foothold = next;
        }
        x = Math.max(foothold.getX1(), Math.min(foothold.getX2(), target));
    }

    public Point position() {
        return canJump ? new Point(legacyRound(x), legacyRound(y))
                : new Point((int) Math.round(x), (int) Math.round(y));
    }
    private static int legacyRound(double value) {
        // MovingObject::get_position uses std::round (negative ties away from zero).
        return (int) (value < 0 ? Math.ceil(value - 0.5) : Math.floor(value + 0.5));
    }
    public int foothold() { return foothold.getId(); }
    // Client stance low bit: even faces right, odd faces left.
    public int stance() {
        int action = hitTicks > 0 ? 8 : jumping ? 6 : walking ? 2 : 4;
        return action + (direction < 0 ? 1 : 0);
    }
}
