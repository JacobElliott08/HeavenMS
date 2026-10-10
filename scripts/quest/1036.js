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
    Quest:       1036 - Robin the Walking Encyclopedia
    NPC:         Robin (2003)
    Map:         Snail Hunting Ground I (40000)
    Description: Educational quest about AP, stats, and job advancement
    
    Uses the new ReplyBuilder API with qm.createReply()
*/

var status = -1;
var quizAnswer = -1;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        qm.dispose();
        return;
    }
    
    if (mode == 0) {
        qm.dispose();
        return;
    }
    
    if (mode == 1)
        status++;
    else
        status--;
    
    // Quest 1036: Robin the Walking Encyclopedia
    var questId = 1036;
    
    if (status == 0) {
        if (qm.isQuestCompleted(questId)) {
            // Already completed
            qm.createReply()
                .text("You've already passed my quiz! You now know all about stats, AP, and job advancement. ")
                .text("Keep training hard!")
                .sendOk();
            qm.dispose();
            
        } else if (qm.isQuestStarted(questId)) {
            // Quest in progress - ready for quiz
            qm.createReply()
                .text("Ah, you're back! Are you ready for the quiz? ")
                .linebreak()
                .linebreak()
                .text("I'll ask you about what I taught you regarding #bAbility Points (AP)#k, #bstats#k, and #bjob advancement#k. ")
                .text("Answer correctly and you'll pass!")
                .sendNext();
            
        } else if (qm.getPlayer().getLevel() <= 10 && qm.getPlayer().getJob().getId() == 0){
            // Can start quest
            qm.createReply()
                .text("Hello there, young adventurer! I'm #bRobin#k, the Walking Encyclopedia of Maple Island. ")
                .linebreak()
                .linebreak()
                .text("I know everything there is to know about #bAbility Points#k, #bstats#k, and #bjob advancement#k. ")
                .text("Would you like me to teach you?")
                .sendAcceptDecline();
            
        } else {
            // Cannot start (wrong level/job)
            qm.createReply()
                .text("Hello! I teach beginners about the fundamentals of character growth. ")
                .text("Come back when you're a beginner level 10 or below! ")
                .text(" You are currently " + qm.getPlayer().getLevel() + " Level ")
                .text(" and job " +  qm.getPlayer().getJob() + " Job ")
                .sendOk();
            qm.dispose();
        }
    }
    
    else if (status == 1) {
        if (qm.isQuestStarted(questId)) {
            // Start the quiz
            qm.createReply()
                .text("Here's your question:")
                .linebreak()
                .linebreak()
                .text("#bWhen you level up, you gain Ability Points (AP). How many AP do you receive per level?#k")
                .linebreak()
                .linebreak()
                .selection(0, "3 AP per level")
                .selection(1, "4 AP per level")
                .selection(2, "5 AP per level")
                .selection(3, "10 AP per level")
                .send();
            
        } else {
            // Teaching phase
            qm.createReply()
                .text("Excellent! Let me teach you about the fundamentals.")
                .linebreak()
                .linebreak()
                .text("#b[Ability Points (AP)]#k")
                .linebreak()
                .text("Every time you level up, you gain #b5 Ability Points#k. You can assign these to four main stats:")
                .linebreak()
                .text("- #bSTR#k (Strength) - Increases physical attack power")
                .linebreak()
                .text("- #bDEX#k (Dexterity) - Increases accuracy and avoidability")
                .linebreak()
                .text("- #bINT#k (Intelligence) - Increases magic attack power")
                .linebreak()
                .text("- #bLUK#k (Luck) - Increases avoidability and critical rate")
                .sendNext();
        }
    }
    
    else if (status == 2) {
        if (qm.isQuestStarted(questId)) {
            // Handle quiz answer
            quizAnswer = selection;
            
            if (quizAnswer == 2) {
                // Correct answer (5 AP per level)
                qm.createReply()
                    .text("#bCorrect!#k You receive #b5 Ability Points#k with each level up!")
                    .linebreak()
                    .linebreak()
                    .text("You've passed my quiz! You now understand the basics of character growth. Keep training hard!")
                    .sendOk();
                qm.completeQuest(questId);
                
            } else {
                // Wrong answer
                qm.createReply()
                    .text("#rIncorrect!#k The correct answer is #b5 Ability Points per level#k.")
                    .linebreak()
                    .linebreak()
                    .text("Study the material I taught you and come back to try again!")
                    .sendOk();
            }
            qm.dispose();
            
        } else {
            // Continue teaching
            qm.createReply()
                .text("#b[Job Advancement]#k")
                .linebreak()
                .text("At level 10, you can choose your first job:")
                .linebreak()
                .text("- #bWarrior#k in Perion (high STR)")
                .linebreak()
                .text("- #bMagician#k in Ellinia (high INT)")
                .linebreak()
                .text("- #bBowman#k in Henesys (high DEX)")
                .linebreak()
                .text("- #bThief#k in Kerning City (high DEX and LUK)")
                .linebreak()
                .linebreak()
                .text("Each job has different stat requirements, so distribute your AP wisely!")
                .sendNext();
        }
    }
    
    else if (status == 3) {
        // Start the quest
        qm.createReply()
            .text("Now that you've learned the basics, let me test your knowledge with a quiz!")
            .linebreak()
            .linebreak()
            .text("Come back when you're ready to be tested!")
            .sendOk();
        qm.startQuest(questId);
        qm.dispose();
    }
    
    else {
        qm.dispose();
    }
}
