package server.npc.interaction;
import client.MapleClient;
import client.MapleCharacter;
import server.life.MapleNPC;

public class NpcInteractionContext {

    private final MapleClient client;
    private final MapleCharacter player;
    private final MapleNPC npc;

    public NpcInteractionContext(MapleClient client, MapleNPC npc) {
        this.client = client;
        this.player = client.getPlayer();
        this.npc = npc;
    }

    public MapleClient getClient() {
        return client;
    }

    public MapleCharacter getPlayer() {
        return player;
    }

    public MapleNPC getNpc() {
        return npc;
    }
}