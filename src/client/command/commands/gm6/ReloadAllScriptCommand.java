package client.command.commands.gm6;

import client.MapleClient;
import client.command.Command;

public class ReloadAllScriptCommand extends Command {
    {
        setDescription("Reloads all javascripts.");
    }

    @Override
    public void execute(MapleClient c, String[] params) {
        c.getPlayer().dropMessage(5, "Reloading all javascripts...");
        //c.getPlayer().get
        c.getPlayer().dropMessage(5, "All javascripts reloaded.");
    }
}
