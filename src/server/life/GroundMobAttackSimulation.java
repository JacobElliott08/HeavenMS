package server.life;

import java.awt.Point;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Approximate single-projectile ground attack. The owning monster lock
 * serializes ticks. No client input supplies target, damage or projectile path.
 */
public final class GroundMobAttackSimulation {
    public static final int SPEED_PX_PER_SECOND = 750, HIT_RADIUS = 18;
    private final GroundMobAttackProfile profile;

    public GroundMobAttackSimulation(GroundMobAttackProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
    }

    public enum Phase { START, LAUNCH, IMPACT, CANCEL }
    public record Event(Phase phase, long attackId, int targetId, Point from, Point to,
                        int duration, boolean left, boolean collision) {}

    private long sequence;
    private int age, targetId;
    private boolean active, launched, resolved, left;
    private Point from, to;
    private int flightMs, flightElapsed;

    /**
     * Target is a server-side eligible aggro target, or null if aggro was lost.
     * targetPosition is feet; the projectile aims at the approximate body centre.
     * blocked includes death, removal, stun and freeze.
     */
    public List<Event> tick(Point mobPosition, int eligibleTargetId, Point targetPosition,
                            boolean grounded, boolean blocked) {
        List<Event> events = new ArrayList<>();
        if (active && (blocked || eligibleTargetId != targetId || targetPosition == null)) {
            events.add(event(Phase.CANCEL, false));
            active = false;
        }
        if (blocked) return events;
        if (!active) {
            if (!grounded || eligibleTargetId <= 0 || targetPosition == null
                    || mobPosition.distanceSq(targetPosition) > profile.range() * profile.range()) return events;
            active = true;
            launched = resolved = false;
            age = flightElapsed = 0;
            targetId = eligibleTargetId;
            left = targetPosition.x < mobPosition.x;
            from = new Point(mobPosition.x + (left ? profile.offsetX() : -profile.offsetX()),
                    mobPosition.y + profile.offsetY());
            to = new Point(targetPosition.x, targetPosition.y - 25);
            ++sequence;
            events.add(event(Phase.START, false));
            return events;
        }
        age += GroundMobSimulation.INTERVAL_MS;
        if (!launched && age >= profile.windupMs()) {
            launched = true;
            to = new Point(targetPosition.x, targetPosition.y - 25);
            flightMs = Math.max(1, (int) Math.ceil(from.distance(to) * 1000.0 / SPEED_PX_PER_SECOND));
            events.add(event(Phase.LAUNCH, false));
        } else if (launched && !resolved) {
            int previous = flightElapsed;
            flightElapsed = Math.min(flightMs, flightElapsed + GroundMobSimulation.INTERVAL_MS);
            double a = Math.min(1.0, previous / (double) flightMs);
            double b = Math.min(1.0, flightElapsed / (double) flightMs);
            double x1 = from.x + (to.x - from.x) * a, y1 = from.y + (to.y - from.y) * a;
            double x2 = from.x + (to.x - from.x) * b, y2 = from.y + (to.y - from.y) * b;
            boolean collision = Line2D.ptSegDist(x1, y1, x2, y2,
                    targetPosition.x, targetPosition.y - 25) <= HIT_RADIUS;
            if (collision || flightElapsed >= flightMs) {
                resolved = true;
                if (collision) {
                    double dx = x2 - x1, dy = y2 - y1;
                    double lengthSq = dx * dx + dy * dy;
                    double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1,
                            ((targetPosition.x - x1) * dx
                                    + (targetPosition.y - 25 - y1) * dy) / lengthSq));
                    to = new Point((int) Math.round(x1 + t * dx), (int) Math.round(y1 + t * dy));
                }
                events.add(event(Phase.IMPACT, collision));
            }
        }
        if (age >= profile.animationMs() + profile.recoveryMs()) active = false;
        return events;
    }

    private Event event(Phase phase, boolean collision) {
        return new Event(phase, sequence, targetId, new Point(from), new Point(to),
                phase == Phase.LAUNCH ? flightMs : 0, left, collision);
    }

    public boolean isAttacking() { return active && age < profile.animationMs(); }
    public boolean isActive() { return active; }
    public boolean isFacingLeft() { return left; }
}
