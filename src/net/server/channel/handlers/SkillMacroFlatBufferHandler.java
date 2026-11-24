package net.server.channel.handlers;

import client.MapleClient;
import protocol.generated.maple.character.SkillMacroUpdate;
import tools.packets.character.SkillMacroPackets;

public final class SkillMacroFlatBufferHandler {
    public void handle(SkillMacroUpdate update, MapleClient client) {
        if (client == null || !client.isLoggedIn() || client.getPlayer() == null) {
            return;
        }

        if (SkillMacroPackets.applyUpdate(update, client.getPlayer())) {
            client.announce(SkillMacroPackets.state(
                    client.getPlayer().getMacros(),
                    client.getPlayer().getKeymap()));
        }
    }
}
