package tools.packets.combat;

import com.google.flatbuffers.FlatBufferBuilder;
import java.util.Collections;
import java.util.List;
import protocol.generated.maple.combat.AttackResult;
import protocol.generated.maple.combat.AttackStatus;
import protocol.generated.maple.combat.AttackTarget;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class CombatPackets {
    private CombatPackets() {}

    public static final class TargetResult {
        public final int oid;
        public final int[] damages;
        public final boolean[] crits;

        public TargetResult(int oid, int[] damages, boolean[] crits) {
            this.oid = oid;
            this.damages = damages;
            this.crits = crits;
        }
    }

    public static byte[] attackResult(long requestId, int attackerId, int skillId, int skillLevel,
                                      int stance, boolean toleft, int speed, int display, int bullet,
                                      int hitCount, List<TargetResult> targets, int status) {
        FlatBufferBuilder b = new FlatBufferBuilder(128);
        int[] targetOffsets = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            TargetResult t = targets.get(i);
            int dmg = AttackTarget.createDamagesVector(b, t.damages);
            int crit = AttackTarget.createCritsVector(b, t.crits);
            targetOffsets[i] = AttackTarget.createAttackTarget(b, t.oid, dmg, crit);
        }
        int targetsVec = AttackResult.createTargetsVector(b, targetOffsets);
        int result = AttackResult.createAttackResult(b, requestId, attackerId, skillId, skillLevel,
                stance, toleft, speed, display, bullet, hitCount, targetsVec, status);
        Packet.startPacket(b);
        Packet.addServerMessageType(b, ServerMessage.protocol_generated_maple_combat_AttackResult);
        Packet.addServerMessage(b, result);
        Packet.finishPacketBuffer(b, Packet.endPacket(b));
        return b.sizedByteArray();
    }

    public static byte[] rejected(long requestId, int attackerId, int skillId, int status) {
        return attackResult(requestId, attackerId, skillId, 0, 0, false, 0, 0, 0, 0,
                Collections.<TargetResult>emptyList(),
                status == AttackStatus.Ok ? AttackStatus.Rejected : status);
    }
}
