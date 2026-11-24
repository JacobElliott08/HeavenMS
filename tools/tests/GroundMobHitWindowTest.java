import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import server.life.GroundMobHitWindow;

public class GroundMobHitWindowTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        long start = 10_000_000_000L, end = start + GroundMobHitWindow.INVULNERABLE_NANOS;
        GroundMobHitWindow legacy = new GroundMobHitWindow();
        check(legacy.recordLegacyTouch(start) && legacy.recordLegacyTouch(start + 1),
                "legacy touches do not throttle each other");
        check(!legacy.claimGroundImpact(end), "latest touch protects against a ground impact");
        check(legacy.claimGroundImpact(end + 1), "boundary permits ground impact");
        check(!legacy.recordLegacyTouch(end + 2), "ground impact protects against touch");
        check(!legacy.claimGroundImpact(end + 2), "ground impact cannot double hit");
        check(legacy.recordLegacyTouch(end + 1 + GroundMobHitWindow.INVULNERABLE_NANOS),
                "touch resumes at boundary");
        GroundMobHitWindow rejected = new GroundMobHitWindow();
        check(rejected.claimGroundImpact(start), "reserve a candidate impact");
        rejected.releaseGroundImpact(start);
        check(rejected.recordLegacyTouch(start + 1), "rejected impact releases the window");
        for (int i = 0; i < 100; ++i) {
            GroundMobHitWindow race = new GroundMobHitWindow();
            CountDownLatch begin = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            Thread a = new Thread(() -> {
                try { begin.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                if (race.recordLegacyTouch(start)) accepted.incrementAndGet();
            });
            Thread b = new Thread(() -> {
                try { begin.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                if (race.claimGroundImpact(start)) accepted.incrementAndGet();
            });
            a.start(); b.start(); begin.countDown(); a.join(); b.join();
            check(accepted.get() == 1, "touch/ground race chooses one");
        }
        System.out.println("PASS ground/touch overlap, boundaries, and concurrent arbitration");
    }
}
