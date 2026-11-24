import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.nio.ByteBuffer;
import provider.MapleDataProviderFactory;
import protocol.generated.maple.map.MobAttackEvent;
import protocol.generated.maple.map.MobAttackPhase;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;
import server.life.GroundMobAttackProfile;
import server.life.GroundMobAttackSimulation;
import server.life.GroundMobAttackSimulation.Event;
import server.life.GroundMobAttackSimulation.Phase;
import tools.packets.map.MobAttackPackets;

/** Deterministic server timeline/collision tests, no network or timer. */
public class GroundMobAttackSimulationTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var wz = MapleDataProviderFactory.getDataProvider(new File("wz/Mob.wz"));
        GroundMobAttackProfile profile = GroundMobAttackProfile.fromWz(wz.getData("3210800.img"), 5);
        GroundMobAttackProfile other = GroundMobAttackProfile.fromWz(wz.getData("4230101.img"), 5);
        check(profile != null && profile.physicalAttack() == 125 && profile.range() == 200
                        && profile.windupMs() == 850 && profile.animationMs() == 2250,
                "Lupin real WZ profile");
        check(other != null && other.physicalAttack() == 135 && other.accuracy() == 110
                        && other.range() == 200 && other.windupMs() == 850,
                "separate real WZ profile parses, without opting Zombie Lupin in");
        check(GroundMobAttackProfile.releaseFrameFor(4230101) == -1, "other template remains legacy");
        GroundMobAttackSimulation sim = new GroundMobAttackSimulation(profile);
        Point mob = new Point(0, 0), target = new Point(140, 0);
        check(sim.tick(mob, 0, null, true, false).isEmpty(), "no aggro");
        List<Event> events = new ArrayList<>(sim.tick(mob, 42, target, true, false));
        check(events.size() == 1 && events.get(0).phase() == Phase.START, "start");
        for (int i = 0; i < 32; ++i) events.addAll(sim.tick(mob, 42, target, true, false));
        check(events.stream().filter(e -> e.phase() == Phase.LAUNCH).count() == 1, "one launch");
        check(events.stream().filter(e -> e.phase() == Phase.IMPACT && e.collision()).count() == 1,
                "one collision");
        GroundMobAttackSimulation early = new GroundMobAttackSimulation(profile);
        early.tick(mob, 42, target, true, false);
        for (int i = 0; i < 9; ++i) early.tick(mob, 42, target, true, false);
        List<Event> earlyEvents = new ArrayList<>();
        for (int i = 0; i < 5; ++i)
            earlyEvents.addAll(early.tick(mob, 42, new Point(80, 0), true, false));
        Event earlyImpact = earlyEvents.stream().filter(e -> e.phase() == Phase.IMPACT && e.collision())
                .findFirst().orElseThrow();
        check(earlyImpact.to().x < 140, "impact reports swept collision point rather than launch endpoint");
        int[] wirePhases = { MobAttackPhase.Start, MobAttackPhase.Launch,
                MobAttackPhase.Impact, MobAttackPhase.Cancel };
        for (Phase phase : Phase.values()) {
            Event event = new Event(phase, 7, 42, new Point(-14, -29),
                    new Point(140, -25), 200, true, phase == Phase.IMPACT);
            ByteBuffer bytes = ByteBuffer.wrap(MobAttackPackets.event(100000000, 55, 9, event, 123));
            check(Packet.PacketBufferHasIdentifier(bytes), "MSFB identifier");
            Packet packet = Packet.getRootAsPacket(bytes);
            check(packet.serverMessageType() == ServerMessage.protocol_generated_maple_map_MobAttackEvent,
                    "generated union discriminator");
            MobAttackEvent decoded = (MobAttackEvent) packet.serverMessage(new MobAttackEvent());
            check(decoded.phase() == wirePhases[phase.ordinal()] && decoded.mapId() == 100000000
                            && decoded.objectId() == 55 && decoded.instance() == 9
                            && decoded.attackId() == 7 && decoded.targetId() == 42
                            && decoded.fromX() == -14 && decoded.toX() == 140
                            && decoded.facingLeft() && decoded.hit() == (phase == Phase.IMPACT),
                    "event roundtrip " + phase);
        }
        GroundMobAttackSimulation miss = new GroundMobAttackSimulation(profile);
        miss.tick(mob, 42, target, true, false);
        for (int i = 0; i < 9; ++i) miss.tick(mob, 42, target, true, false);
        events.clear();
        for (int i = 0; i < 5; ++i) events.addAll(miss.tick(mob, 42, new Point(140, 90), true, false));
        check(events.stream().filter(e -> e.phase() == Phase.IMPACT && !e.collision()).count() == 1,
                "moving out of line misses");
        GroundMobAttackSimulation cancel = new GroundMobAttackSimulation(profile);
        cancel.tick(mob, 42, target, true, false);
        check(cancel.tick(mob, 42, target, true, true).get(0).phase() == Phase.CANCEL,
                "blocked cancels windup");
        check(cancel.tick(mob, 42, target, true, true).isEmpty(), "blocked cannot restart");
        cancel.tick(mob, 42, target, true, false);
        check(cancel.tick(mob, 0, null, true, false).get(0).phase() == Phase.CANCEL, "aggro lost");
        GroundMobAttackSimulation second = new GroundMobAttackSimulation(other);
        check(second.tick(mob, 99, target, true, false).get(0).phase() == Phase.START, "other WZ attack start");
        events.clear();
        for (int i = 0; i < 15; ++i) events.addAll(second.tick(mob, 99, target, true, false));
        check(events.stream().anyMatch(e -> e.phase() == Phase.IMPACT && e.collision()),
                "same simulator resolves other real WZ profile");
        System.out.println("PASS reusable WZ profiles, MSFB event roundtrip, windup, collision, miss, interruption and aggro loss");
    }
}
