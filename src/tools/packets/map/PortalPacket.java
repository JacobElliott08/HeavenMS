package tools.packets.map;

import protocol.generated.maple.common.Vec2;
import protocol.generated.maple.map.SpawnPortal;
import com.google.flatbuffers.FlatBufferBuilder;

import java.awt.*;

public class PortalPacket {

    public static byte[] spawnPortal(int townId, int targetId, Point pos)
    {
        FlatBufferBuilder builder = new FlatBufferBuilder(0);
        int position = Vec2.createVec2(builder, pos.x, pos.y);

        SpawnPortal.startSpawnPortal(builder);
        SpawnPortal.addPosition(builder, position);
        SpawnPortal.addTownId(builder, townId);
        SpawnPortal.addTargetId(builder, targetId);

        int portal = SpawnPortal.endSpawnPortal(builder);
        builder.finish(portal);
        return builder.sizedByteArray();
    }
}
