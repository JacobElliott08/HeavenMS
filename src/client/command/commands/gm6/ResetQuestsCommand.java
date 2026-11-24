package client.command.commands.gm6;

import client.MapleCharacter;
import client.MapleClient;
import client.command.Command;

import java.sql.SQLException;

public class ResetQuestsCommand extends Command {
    {
        setDescription("");
    }

    @Override
    public void execute(MapleClient c, String[] params) {
        MapleCharacter player = c.getPlayer();
        try {
            player.resetQuests();
            player.dropMessage(5, "All quests reset. Relog to refresh the client quest log.");
        } catch (SQLException e) {
            e.printStackTrace();
            player.dropMessage(5, "Failed to reset quests.");
        }
    }
}
