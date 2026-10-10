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
package net.server.channel.handlers;

import net.AbstractMaplePacketHandler;
import server.maps.MapleMapObject;
import tools.data.input.SeekableLittleEndianAccessor;
import client.MapleCharacter;
import client.MapleClient;
import java.awt.Point;
import tools.FilePrinter;

/**
 *
 * @author Matze
 * @author Ronan
 */
public final class ItemPickupHandler extends AbstractMaplePacketHandler {

    @Override
    public final void handlePacket(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        slea.readInt(); //Timestamp
        slea.readByte();
        slea.readPos(); //cpos
        int oid = slea.readInt();
        MapleCharacter chr = c.getPlayer();
        
        System.out.println("[ItemPickupHandler] Player: " + chr.getName() + " (ID: " + chr.getId() + ") attempting to pickup OID: " + oid);
        
        MapleMapObject ob = chr.getMap().getMapObject(oid);
        if(ob == null) {
            System.out.println("[ItemPickupHandler] Object not found for OID: " + oid + " - returning");
            return;
        }
        
        Point charPos = chr.getPosition();
        Point obPos = ob.getPosition();
        System.out.println("[ItemPickupHandler] Distance check - Player: " + charPos + ", Object: " + obPos);
        
        if (Math.abs(charPos.getX() - obPos.getX()) > 800 || Math.abs(charPos.getY() - obPos.getY()) > 600) {
            System.out.println("[ItemPickupHandler] Player too far from item - REJECTED");
            FilePrinter.printError(FilePrinter.EXPLOITS + c.getPlayer().getName() + ".txt", c.getPlayer().getName() + " tried to pick up an item too far away. Mapid: " + chr.getMapId() + " Player pos: " + charPos + " Object pos: " + obPos);
            return;
        }
        
        System.out.println("[ItemPickupHandler] Calling chr.pickupItem() for OID: " + oid);
        chr.pickupItem(ob);
        System.out.println("[ItemPickupHandler] chr.pickupItem() completed for OID: " + oid);
    }
}
