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

import config.YamlConfig;
import net.AbstractMaplePacketHandler;
import client.inventory.manipulator.MapleInventoryManipulator;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;
import client.MapleCharacter;
import client.MapleClient;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.ModifyInventory;
import java.util.List;
import net.server.Server;
import server.MapleItemInformationProvider;

public final class InventoryMergeHandler extends AbstractMaplePacketHandler {

    @Override
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        MapleCharacter chr = c.getPlayer();
        slea.readInt();
        chr.getAutobanManager().setTimestamp(2, Server.getInstance().getCurrentTimestamp(), 4);
        
        if(!YamlConfig.config.server.USE_ITEM_SORT) {
            c.announce(MaplePacketCreator.enableActions());
            return;
	}
        
        byte invType = slea.readByte();
        if (invType < 1 || invType > 5) {
            c.disconnect(false, false);
            return;
        }
        
        MapleInventoryType inventoryType = MapleInventoryType.getByType(invType);	
	MapleInventory inventory = c.getPlayer().getInventory(inventoryType);
        inventory.lockInventory();
        try {
            MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            InventoryGathering.mergeStacks(inventory, inventoryType,
                    itemId -> ii.getSlotMax(c, itemId),
                    (src, dst) -> MapleInventoryManipulator.move(c, inventoryType, src, dst));
            List<ModifyInventory> modifications = InventoryGathering.compactAndSort(
                    inventory, ii::getEquipLevelReq, ii::getName);
            if (!modifications.isEmpty()) {
                c.announce(MaplePacketCreator.modifyInventory(true, modifications));
            }
        } finally {
            inventory.unlockInventory();
        }
        
        c.announce(MaplePacketCreator.finishedSort(inventoryType.getType()));
        c.announce(MaplePacketCreator.enableActions());
    }
}