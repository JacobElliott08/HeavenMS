package tools.packets.map;

import protocol.generated.maple.common.Vec2;
import protocol.generated.maple.map.RemovePortal;
import com.google.flatbuffers.FlatBufferBuilder;

import java.awt.*;

public class RemovePortalPacket {

    public static byte[] removePortal(int targetId)
    {
        FlatBufferBuilder builder = new FlatBufferBuilder(0);

        RemovePortal.startRemovePortal(builder);
        RemovePortal.addTargetId(builder, targetId);

        int portal = RemovePortal.endRemovePortal(builder);
        builder.finish(portal);
        return builder.sizedByteArray();
    }
}
