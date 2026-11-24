package server.life;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges the client's existing two-second touch invulnerability with
 * server-owned ground impacts. Legacy touches never throttle each other here.
 */
public final class GroundMobHitWindow {
    public static final long INVULNERABLE_NANOS = 2_000_000_000L;
    private record State(boolean touch, long touchTime, boolean ground, long groundTime) {}
    private final AtomicReference<State> state = new AtomicReference<>(new State(false, 0, false, 0));

    private static boolean recent(boolean present, long time, long now) {
        return present && now - time >= 0 && now - time < INVULNERABLE_NANOS;
    }

    /** Returns false only when a server-owned impact already owns the window. */
    public boolean recordLegacyTouch(long now) {
        for (;;) {
            State old = state.get();
            if (recent(old.ground, old.groundTime, now)) return false;
            State next = new State(true, now, old.ground, old.groundTime);
            if (state.compareAndSet(old, next)) return true;
        }
    }

    /** Ground impacts cannot overlap a recently accepted touch or ground impact. */
    public boolean claimGroundImpact(long now) {
        for (;;) {
            State old = state.get();
            if (recent(old.touch, old.touchTime, now) || recent(old.ground, old.groundTime, now)) return false;
            State next = new State(old.touch, old.touchTime, true, now);
            if (state.compareAndSet(old, next)) return true;
        }
    }

    /** Undo a rejected reservation, without clearing a newer successful hit. */
    public void releaseGroundImpact(long time) {
        for (;;) {
            State old = state.get();
            if (!old.ground || old.groundTime != time) return;
            State next = new State(old.touch, old.touchTime, false, 0);
            if (state.compareAndSet(old, next)) return;
        }
    }
}
