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

import client.MapleCharacter;
import client.MapleClient;
import client.Skill;
import client.SkillFactory;
import client.inventory.Item;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import java.util.Map;
import net.AbstractMaplePacketHandler;
import client.inventory.manipulator.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;
import protocol.generated.maple.character.SkillBookUseRequest;
import protocol.generated.maple.character.SkillBookUseStatus;
import tools.packets.character.SkillBookPackets;
import tools.Randomizer;

public final class SkillBookHandler extends AbstractMaplePacketHandler {
    @Override
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        // Retain the registered legacy adapter, sharing the same validation.
        slea.readInt();
        useBook(slea.readShort(), slea.readInt(), c, false);
    }

    public void handleFlatBufferRequest(SkillBookUseRequest request, MapleClient c) {
        if (c == null || request == null || c.getPlayer() == null || !validateState(c)) {
            return;
        }
        // Read all untrusted fields before mutating inventory.
        short slot = request.slot();
        int itemId = request.itemId();
        handleUseItemRequest(slot, itemId, c);
    }

    // Older versions of this C++ client sent books through USE_ITEM. Keep
    // those clicks on the same authoritative path and migrated result contract.
    void handleUseItemRequest(short slot, int itemId, MapleClient c) {
        if (c == null || c.getPlayer() == null || !validateState(c)) {
            return;
        }
        useBook(slot, itemId, c, true);
    }

    private void useBook(short slot, int itemId, MapleClient c, boolean flatBuffer) {
        MapleCharacter player = c.getPlayer();
        int status = SkillBookUseStatus.Rejected;
        int skill = 0;
        int maxlevel = 0;
        if (!c.tryacquireClient()) {
            sendResult(c, itemId, skill, maxlevel, status, flatBuffer);
            return;
        }
        try {
            if (!player.isAlive() || player.isChangingMaps() || player.isAwayFromWorld() || player.getTrade() != null
                    || slot <= 0 || !isSkillBook(itemId)) {
                return;
            }
            MapleInventory inv = player.getInventory(MapleInventoryType.USE);
            // Hold the existing inventory lock from validation through consumption
            // and grant: a stale slot must never consume a replacement item.
            inv.lockInventory();
            try {
                Item toUse = inv.getItem(slot);
                if (!matchesBook(toUse, slot, itemId)) {
                    return;
                }
                Map<String, Integer> skilldata = MapleItemInformationProvider.getInstance().getSkillStats(toUse.getItemId(), player.getJob().getId());
                if (skilldata == null) {
                    return;
                }

                skill = skilldata.getOrDefault("skillid", 0);
                maxlevel = skilldata.getOrDefault("masterLevel", 0);
                Skill skill2 = skill > 0 ? SkillFactory.getSkill(skill) : null;
                boolean canuse = skill2 != null && maxlevel <= skill2.getMaxLevel() && meetsBookRequirements(
                        player.getSkillLevel(skill2),
                        player.getMasterLevel(skill2),
                        skilldata.getOrDefault("reqSkillLevel", 0),
                        maxlevel);

                if (canuse) {
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
                    status = SkillBookUseStatus.Failed;
                    // Book chance is the WZ percentage, not the configurable
                    // scroll multiple-roll bonus in rollSuccessChance.
                    if (succeeds(skilldata.getOrDefault("success", 0), Randomizer.nextInt(100))) {
                        player.changeSkillLevel(skill2, player.getSkillLevel(skill2),
                                Math.max(maxlevel, player.getMasterLevel(skill2)), -1);
                        status = SkillBookUseStatus.Success;
                    }
                }
            } finally {
                inv.unlockInventory();
            }
        } finally {
            c.releaseClient();
            sendResult(c, itemId, skill, maxlevel, status, flatBuffer);
        }
    }

    private void sendResult(MapleClient c, int itemId, int skill, int maxlevel, int status, boolean flatBuffer) {
        MapleCharacter player = c.getPlayer();
        boolean attempted = status != SkillBookUseStatus.Rejected;
        byte[] result = flatBuffer
                ? SkillBookPackets.result(player.getId(), itemId, skill, maxlevel, status)
                : MaplePacketCreator.skillBookResult(player, skill, maxlevel, attempted, status == SkillBookUseStatus.Success);
        c.announce(result);
        if (attempted && player.getMap() != null) {
            // Exclude the owner, who already received exactly one result.
            player.getMap().broadcastMessage(player, result);
        }
    }

    static boolean isSkillBook(int itemId) {
        return itemId / 10000 == 228 || itemId / 10000 == 229;
    }

    static boolean matchesBook(Item item, short slot, int itemId) {
        return slot > 0 && isSkillBook(itemId) && item != null
                && item.getPosition() == slot && item.getItemId() == itemId && item.getQuantity() > 0;
    }

    static boolean succeeds(int chance, int roll) {
        return roll >= 0 && roll < 100 && roll < chance;
    }

    static boolean meetsBookRequirements(int skillLevel, int currentMasterLevel,
                                         int requiredSkillLevel, int bookMasterLevel) {
        return requiredSkillLevel >= 0 && bookMasterLevel > 0 && skillLevel >= requiredSkillLevel
                && currentMasterLevel < bookMasterLevel;
    }
}
