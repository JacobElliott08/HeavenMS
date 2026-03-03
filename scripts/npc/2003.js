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
/*
    NPC:         Robin (2003)
    Map:         Maple Road : Snail Hunting Ground I (40000)
    Description: Beginner Helper

    Rewritten for the binary NPC dialogue protocol.
    Uses cm.createReply() — no legacy markup strings.

    Conversation state machine
    ──────────────────────────
    status  -1  → not started
    status   0  → page 1 of selected topic  (sendNext / sendOk)
    status   1  → page 2 of selected topic  (sendNextPrev / back to menu)

    action() receives the binary response fields:
      mode      0 = ACTION_CANCEL   1 = ACTION_OK/NEXT   2 = ACTION_PREV
      type      DialogueType.code echoed from the server packet
      selection index of the clicked entry (SELECTION type only)
*/

var status = -1;
var sel    = -1;

// ── Topic labels shown in the selection menu ─────────────────────────────────

var TOPICS = [
    "How do I move?",
    "How do I take down the monsters?",
    "How can I pick up an item?",
    "What happens when I die?",
    "When can I choose a job?",
    "Tell me more about this island!",
    "What should I do to become a Warrior?",
    "What should I do to become a Bowman?",
    "What should I do to become a Magician?",
    "What should I do to become a Thief?",
    "How do I raise the character stats?",
    "How do I check the items I just picked up?",
    "How do I put on an item?",
    "How do I check out the items I am wearing?",
    "What are skills?",
    "How do I get to Victoria Island?",
    "What are mesos?"
];

// ── Page 1 — shown first for every topic ─────────────────────────────────────

var PAGE_1 = [
    // 0 — movement
    "Use the left and right arrow keys to move around and press Alt to jump. " +
    "A select number of shoes improve your speed and jumping abilities.",

    // 1 — fighting
    "Every monster has its own HP. You take them down by attacking with a weapon " +
    "or through spells. The stronger they are, the harder they are to beat.",

    // 2 — item pickup
    "Once you defeat a monster an item drops to the ground. Stand in front of it " +
    "and press Z or 0 on the NumPad to pick it up.",

    // 3 — death
    "When your HP reaches 0 you become a ghost. A tombstone marks the spot and " +
    "you cannot move, though you can still chat.",

    // 4 — job choice
    "Each job has requirements you must meet before advancing. " +
    "Normally a level between 8 and 10 will do, so work hard.",

    // 5 — the island
    "This place is called Maple Island and it floats in the air. " +
    "It has been floating peacefully for a long time — perfect for beginners!",

    // 6 — Warrior
    "Head to Victoria Island and find the warrior-town Perion. " +
    "Seek out Dances with Balrog — he will teach you everything about becoming a Warrior. " +
    "You must be at least level 10.",

    // 7 — Bowman
    "Travel to Victoria Island and find the bowman-town Henesys. " +
    "Speak with Athena Pierce to learn the way of the Bowman. " +
    "You must be at least level 10.",

    // 8 — Magician
    "Head to Victoria Island and find the magician-town Ellinia. " +
    "At the very top is the Magic Library where Grendel the Really Old " +
    "will teach you everything about becoming a Magician.",

    // 9 — Thief
    "Travel to Victoria Island and find the thief-town Kerning City. " +
    "On the shadier side of town is a hideaway where Dark Lord will " +
    "teach you everything about being a Thief. You must be at least level 10.",

    // 10 — stats
    "Press S to open the ability window. Every time you level up you receive " +
    "5 ability points. Assign those AP to the stat of your choice.",

    // 11 — checking inventory
    "Defeat a monster and press Z to pick up the drop. " +
    "Press I to open your item inventory and see what you collected.",

    // 12 — equipping
    "Press I to open your item inventory. Double-click an item to equip it. " +
    "If you cannot wear it your character does not meet the requirements. " +
    "You can also drag items into the equipment window (E). " +
    "Double-click a worn item to unequip it.",

    // 13 — viewing equipment
    "Press E to open the equipment inventory and see exactly what you are " +
    "wearing right now. Double-click a worn item to send it back to your inventory.",

    // 14 — skills
    "The special abilities you gain after choosing a job are called skills. " +
    "Press K to open the skill book. You have none yet, but you will after " +
    "your first job advancement.",

    // 15 — Victoria Island
    "On the east side of this island is a harbor called Southperry. " +
    "An airship is docked there. The captain in front of it can tell you more.",

    // 16 — mesos
    "Mesos are the currency of MapleStory. Earn them by defeating monsters, " +
    "selling items at shops, or completing quests."
];

// ── Page 2 — null means the topic only has one page ──────────────────────────

var PAGE_2 = [
    // 0 — movement (page 2)
    "When equipped, press Ctrl to attack with your weapon. " +
    "With good timing you will take down monsters quickly.",

    // 1 — fighting (page 2)
    "After your job advancement you will gain skills you can assign to hotkeys. " +
    "Attacking skills do not require Ctrl — just the hotkey.",

    // 2 — item pickup (page 2)
    "If your item inventory is full you cannot pick up more items. " +
    "Sell anything you do not need. The inventory may expand after your first job advancement.",

    // 3 — death (page 2)
    "There is not much to lose as a beginner. Once you have a job, however, " +
    "you will lose EXP on death. Stay safe and avoid danger.",

    // 4 — job choice (page 2)
    "Your level is not the only requirement. Each job also needs a minimum stat. " +
    "Warriors need STR over 35, for example. Invest your AP with your intended job in mind.",

    // 5 — the island (page 2)
    "If you want to grow powerful do not linger here too long. " +
    "You cannot advance to a job on Maple Island. " +
    "Victoria Island below you is vastly larger and full of opportunity.",

    // 6 — Warrior: no page 2
    null,

    // 7 — Bowman: no page 2
    null,

    // 8 — Magician (page 2)
    "Unlike other jobs you only need level 8 to become a Magician. " +
    "The earlier advancement is offset by the effort required to master the class. " +
    "Choose carefully.",

    // 9 — Thief: no page 2
    null,

    // 10 — stats (page 2)
    "Hover over each stat for a brief description. " +
    "STR for Warriors, DEX for Bowmen, INT for Magicians, LUK for Thieves. " +
    "Plan your assignments around the job you want.",

    // 11 — checking inventory: no page 2
    null,

    // 12 — equipping: no page 2
    null,

    // 13 — viewing equipment: no page 2
    null,

    // 14 — skills: no page 2
    null,

    // 15 — Victoria Island (page 2)
    "One last tip: if you are ever lost press W to open the world map. " +
    "The locator shows exactly where you are. You will never have to worry about getting lost.",

    // 16 — mesos: no page 2
    null
];

// ── Script entry points ───────────────────────────────────────────────────────

function start() {
    status = -1;
    sel    = -1;

    var reply = cm.createReply().text("Now... ask me any questions you may have on traveling!").linebreak();
    for (var i = 0; i < TOPICS.length; i++) {
        reply.selection(TOPICS[i]);
    }
    reply.send();
}

function action(mode, type, selection) {
    if (mode == 0) {        // ACTION_CANCEL — player closed window
        cm.dispose();
        return;
    }

    if (mode == 2) {        // ACTION_PREV — navigate back one page
        status--;
    } else {                // ACTION_OK / ACTION_NEXT — advance
        status++;
    }

    if (status == 0) {
        // Record which topic was chosen on the way in from the menu.
        if (sel == -1) sel = selection;

        if (sel < 0 || sel >= PAGE_1.length) {
            start();
            return;
        }

        if (PAGE_2[sel] != null) {
            cm.createReply().text(PAGE_1[sel]).sendNext();
        } else {
            cm.createReply().text(PAGE_1[sel]).sendOk();
        }

    } else if (status == 1) {
        var page2 = (sel >= 0 && sel < PAGE_2.length) ? PAGE_2[sel] : null;

        if (page2 != null) {
            cm.createReply().text(page2).sendNextPrev();
        } else {
            start();
        }

    } else {
        start();
    }
}