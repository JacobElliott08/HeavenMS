/*
 * This file is part of the HeavenMS Maple Story Server
 *
 * Copyright (C) 2019 Jake
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client.command.commands.gm6;

import client.MapleCharacter;
import client.MapleClient;
import client.command.Command;

/**
 *
 * @author Jake
 */
public class DeleteCharacterCommand  extends Command{
    
    @Override
    public void execute(MapleClient c, String[] params) {
        MapleCharacter player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage("Syntax: !delchar <playername> <charid>");
            return;
}
        MapleCharacter victim = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        int accid = Integer.parseInt(params[1]);
        if (victim != null) {
            if(MapleCharacter.deleteCharFromDB(victim, accid))
            {
                player.dropMessage("deleted");
            }
            else
            {
                player.dropMessage("not deleted");
            }
        }else {
            player.message("Player '" + params[0] + "' could not be found on this world.");
        }
        
        
    }
    
}
