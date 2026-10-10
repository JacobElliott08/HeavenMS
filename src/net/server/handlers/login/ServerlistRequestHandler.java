/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.handlers.login;

import client.MapleClient;
import constants.game.GameConstants;
import java.util.List;
import net.AbstractMaplePacketHandler;
import net.server.Server;
import net.server.world.World;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.packets.login.ServerListPacket;

public final class ServerlistRequestHandler extends AbstractMaplePacketHandler {

    @Override
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
    	Server server = Server.getInstance();
        List<World> worlds = server.getWorlds();
        System.out.println("[LoginFlow][Serverlist] request received opcode=11 worlds=" + worlds.size());
        c.requestedServerlist(worlds.size());
        
        for (World world : worlds) {
            byte[] packet = MaplePacketCreator.getServerList(world.getId(), GameConstants.WORLD_NAMES[world.getId()], world.getFlag(), world.getEventMessage(), world.getChannels());
            System.out.println("[LoginFlow][Serverlist] before world=" + world.getId() + " bytes=" + packet.length);
            c.announce(packet);
            System.out.println("[LoginFlow][Serverlist] after world=" + world.getId());
        }
        byte[] endOfList = MaplePacketCreator.getEndOfServerList();
        System.out.println("[LoginFlow][Serverlist] before end-of-list bytes=" + endOfList.length);
        c.announce(endOfList);
        System.out.println("[LoginFlow][Serverlist] after end-of-list");

        byte[] selectedWorld = MaplePacketCreator.selectWorld(0);//too lazy to make a check lol
        System.out.println("[LoginFlow][Serverlist] before selectWorld bytes=" + selectedWorld.length);
        c.announce(selectedWorld);
        System.out.println("[LoginFlow][Serverlist] after selectWorld");

        byte[] recommended = MaplePacketCreator.sendRecommended(server.worldRecommendedList());
        System.out.println("[LoginFlow][Serverlist] before recommended bytes=" + recommended.length);
        c.announce(recommended);
        System.out.println("[LoginFlow][Serverlist] after recommended");
    }

    public final void handleFlatBufferRequest(MapleClient c) {
        Server server = Server.getInstance();
        List<World> worlds = server.getWorlds();
        System.out.println("[LoginFlow][Serverlist] FlatBuffers request worlds=" + worlds.size());
        c.requestedServerlist(worlds.size());
        for (World world : worlds) {
            c.announce(ServerListPacket.world(world));
        }
        c.announce(ServerListPacket.end());
        c.announce(ServerListPacket.selectedWorld(0));
        c.announce(ServerListPacket.recommended(server.worldRecommendedList()));
    }
}