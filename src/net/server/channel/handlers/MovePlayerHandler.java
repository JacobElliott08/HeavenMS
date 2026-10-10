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

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import client.MapleCharacter;
import client.MapleClient;
import protocol.generated.maple.map.MovementFragment;
import protocol.generated.maple.map.MovementFragmentType;
import protocol.generated.maple.map.PlayerMove;
import server.movement.AbsoluteLifeMovement;
import server.movement.ChangeEquip;
import server.movement.JumpDownMovement;
import server.movement.LifeMovementFragment;
import server.movement.RelativeLifeMovement;
import server.movement.TeleportMovement;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.exceptions.EmptyMovementException;

public final class MovePlayerHandler extends AbstractMovementPacketHandler {
    @Override
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        slea.skip(9);
        try {   // thanks Sa for noticing empty movement sequences crashing players
            long movementDataStart = slea.getPosition();
            updatePosition(slea, c.getPlayer(), 0);
            long movementDataLength = slea.getPosition() - movementDataStart; //how many bytes were read by updatePosition
            slea.seek(movementDataStart);
            
            c.getPlayer().getMap().movePlayer(c.getPlayer(), c.getPlayer().getPosition());
            if (c.getPlayer().isHidden()) {
                c.getPlayer().getMap().broadcastGMMessage(c.getPlayer(), MaplePacketCreator.movePlayer(c.getPlayer().getId(), slea, movementDataLength), false);
            } else {
                c.getPlayer().getMap().broadcastMessage(c.getPlayer(), MaplePacketCreator.movePlayer(c.getPlayer().getId(), slea, movementDataLength), false);
            }
        } catch (EmptyMovementException e) {}
    }

    public void handleFlatBufferMove(PlayerMove move, MapleClient c) {
        if (c == null || c.getPlayer() == null) {
            return;
        }
        MapleCharacter player = c.getPlayer();
        int fragmentCount = move.fragmentsLength();
        if (fragmentCount < 1) {
            return;
        }

        List<LifeMovementFragment> fragments = new ArrayList<>(fragmentCount);
        for (int i = 0; i < fragmentCount; i++) {
            MovementFragment fragment = move.fragments(i);
            LifeMovementFragment parsed = toLifeMovementFragment(fragment);
            if (parsed != null) {
                fragments.add(parsed);
                applyToTarget(fragment, player);
            }
        }
        if (fragments.isEmpty()) {
            return;
        }

        player.getMap().movePlayer(player, player.getPosition());

        byte[] packet = MaplePacketCreator.movePlayer(player.getId(), fragments);
        if (player.isHidden()) {
            player.getMap().broadcastGMMessage(player, packet, false);
        } else {
            player.getMap().broadcastMessage(player, packet, false);
        }
    }

    private LifeMovementFragment toLifeMovementFragment(MovementFragment fragment) {
        Point position = new Point(fragment.x(), fragment.y());
        switch (fragment.fragmentType()) {
            case MovementFragmentType.Absolute: {
                AbsoluteLifeMovement alm = new AbsoluteLifeMovement(fragment.command(), position, fragment.duration(), fragment.newstate());
                alm.setFh(fragment.fh());
                alm.setPixelsPerSecond(new Point(fragment.wobbleX(), fragment.wobbleY()));
                return alm;
            }
            case MovementFragmentType.Relative:
                return new RelativeLifeMovement(fragment.command(), position, fragment.duration(), fragment.newstate());
            case MovementFragmentType.Teleport: {
                TeleportMovement tm = new TeleportMovement(fragment.command(), position, fragment.newstate());
                tm.setPixelsPerSecond(new Point(fragment.wobbleX(), fragment.wobbleY()));
                return tm;
            }
            case MovementFragmentType.JumpDown: {
                JumpDownMovement jdm = new JumpDownMovement(fragment.command(), position, fragment.duration(), fragment.newstate());
                jdm.setFh(fragment.fh());
                jdm.setPixelsPerSecond(new Point(fragment.wobbleX(), fragment.wobbleY()));
                jdm.setOriginFh(fragment.originFh());
                return jdm;
            }
            case MovementFragmentType.ChangeEquip:
                return new ChangeEquip(fragment.equipSlot());
            default:
                return null;
        }
    }

    // Mirrors AbstractMovementPacketHandler.updatePosition's per-fragment server-side effects.
    private void applyToTarget(MovementFragment fragment, MapleCharacter target) {
        switch (fragment.fragmentType()) {
            case MovementFragmentType.Absolute:
                target.setPosition(new Point(fragment.x(), fragment.y()));
                target.setStance(fragment.newstate());
                break;
            case MovementFragmentType.Relative:
            case MovementFragmentType.Teleport:
            case MovementFragmentType.JumpDown:
                target.setStance(fragment.newstate());
                break;
            default:
                break;
        }
    }
}

