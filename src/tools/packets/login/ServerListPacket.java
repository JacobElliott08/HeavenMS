package tools.packets.login;

import com.google.flatbuffers.FlatBufferBuilder;
import java.util.List;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import tools.Pair;
import constants.game.GameConstants;
import protocol.generated.maple.login.LoginChannel;
import protocol.generated.maple.login.LoginRecommendedWorld;
import protocol.generated.maple.login.LoginRecommendedWorlds;
import protocol.generated.maple.login.LoginSelectedWorld;
import protocol.generated.maple.login.LoginServerListEnd;
import protocol.generated.maple.login.LoginWorld;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class ServerListPacket {
    private ServerListPacket() {}

    public static byte[] world(World world) {
        FlatBufferBuilder builder = new FlatBufferBuilder(2048);
        List<Channel> channels = world.getChannels();
        int[] channelOffsets = new int[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
                int name = builder.createString(GameNames.worldChannelName(GameConstants.WORLD_NAMES[world.getId()], channel.getId()));
            channelOffsets[i] = LoginChannel.createLoginChannel(builder, name, channel.getChannelCapacity(), world.getId(), channel.getId() - 1, false);
        }
        int channelsVector = LoginWorld.createChannelsVector(builder, channelOffsets);
        int name = builder.createString(GameConstants.WORLD_NAMES[world.getId()]);
        int eventMessage = builder.createString(world.getEventMessage());
        int worldOffset = LoginWorld.createLoginWorld(builder, world.getId(), name, world.getFlag(), eventMessage, channelsVector);
        return serverMessage(builder, ServerMessage.protocol_generated_maple_login_LoginWorld, worldOffset);
    }

    public static byte[] end() {
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        LoginServerListEnd.startLoginServerListEnd(builder);
        int message = LoginServerListEnd.endLoginServerListEnd(builder);
        return serverMessage(builder, ServerMessage.protocol_generated_maple_login_LoginServerListEnd, message);
    }

    public static byte[] selectedWorld(int worldId) {
        FlatBufferBuilder builder = new FlatBufferBuilder(128);
        int message = LoginSelectedWorld.createLoginSelectedWorld(builder, worldId);
        return serverMessage(builder, ServerMessage.protocol_generated_maple_login_LoginSelectedWorld, message);
    }

    public static byte[] recommended(List<Pair<Integer, String>> recommendations) {
        FlatBufferBuilder builder = new FlatBufferBuilder(512);
        int[] worldOffsets = new int[recommendations.size()];
        for (int i = 0; i < recommendations.size(); i++) {
            Pair<Integer, String> recommendation = recommendations.get(i);
            int reason = builder.createString(recommendation.getRight());
            worldOffsets[i] = LoginRecommendedWorld.createLoginRecommendedWorld(builder, recommendation.getLeft(), reason);
        }
        int worlds = LoginRecommendedWorlds.createWorldsVector(builder, worldOffsets);
        int message = LoginRecommendedWorlds.createLoginRecommendedWorlds(builder, worlds);
        return serverMessage(builder, ServerMessage.protocol_generated_maple_login_LoginRecommendedWorlds, message);
    }

    private static byte[] serverMessage(FlatBufferBuilder builder, byte messageType, int message) {
        int packet = Packet.createPacket(builder, (byte) 1, (byte) 1, 0L, messageType, message, (byte) 0, 0);
        builder.finish(packet, "MSFB");
        byte[] out = builder.sizedByteArray();
        System.out.println("[FlatBuffers->" + ServerMessage.name(messageType) + "] bytes=" + out.length);
        return out;
    }

    private static final class GameNames {
        private static String worldName(World world) {
            return GameConstants.WORLD_NAMES[world.getId()];
        }

        private static String worldChannelName(String worldName, int channelId) {
            return worldName + "-" + channelId;
        }
    }
}