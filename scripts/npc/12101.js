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
/* Author: Xterminator
	NPC Name: 		Rain
	Map(s): 		Maple Road : Amherst (1010000)
	Description: 		Talks about Amherst
*/
var page = 0;
var TextStyle = Java.type("scripting.npc.TextStyle");

function show() {
    if (page == 0)
        cm.createReply()
            .text("This is the town called Amherst, located at the northeast part of the Maple Island. You know that Maple Island is for beginners, right? If you dream of becoming a ")
            .text("Bowman", TextStyle.GREEN)
            .text(" someday, this is a safe place to start. I'm glad there are only weak monsters around this place.")
            .sendNext();
    else if (page == 1)
        cm.createReply()
            .text("If you want to get stronger, then go to ")
            .text("Southperry", TextStyle.BLUE)
            .text(" where there's a harbor. Ride on the gigantic ship and head to the place called ")
            .text("Victoria Island", TextStyle.BLUE)
            .text(". It's incomparable in size compared to this tiny island.")
            .sendNextPrev();
    else
        cm.createReply()
            .text("At the Victoria Island, you can choose your job. Is it called ")
            .text("Perion", TextStyle.BLUE)
            .text("...? I heard there's a bare, desolate town where warriors live. A highland...what kind of a place would that be?")
            .sendPrev();
}

function start() {
    page = 0;
    show();
}

function action(mode, type, selection) {
    if (mode == 1)
        page++;
    else if (mode == 0 && page > 0)
        page--;
    else {
        cm.dispose();
        return;
    }
    show();
}