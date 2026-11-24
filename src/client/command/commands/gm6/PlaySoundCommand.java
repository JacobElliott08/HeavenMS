package client.command.commands.gm6;

import client.MapleCharacter;
import client.MapleClient;
import client.command.Command;
import tools.packets.character.PlaySoundPackets;

public class PlaySoundCommand extends Command {
    {
        setDescription("");
    }

    @Override
    public void execute(MapleClient c, String[] params) {
        MapleCharacter player = c.getPlayer();
        if (params.length < 1) {
            player.dropMessage(5, "Usage: !playsound <path>, e.g. Game.img/QuestAlert");
            return;
        }
        c.announce(PlaySoundPackets.playSound(params[0]));
    }
}
