/*
    This file is part of the HeavenMS MapleStory Server
    
    Quest: Todd's Hunting Method
    Quest ID: 1035
    
    Description: 
    Todd teaches the player how to hunt monsters by asking them to defeat Jr. Sentinels.
    The player must kill 10 Jr. Sentinels (mob ID: 9300018) and collect Jr. Sentinel Shellpiece.
 */

var status = -1;

function start(mode, type, selection) {
    if (mode == -1) {
        qm.dispose();
        return;
    }
    
    if (mode == 0 && type > 0) {
        qm.createReply()
            .text("Hmmm... are you afraid to go up against a monster? Don't worry about it. Practice makes perfect, and you'll be good at it in no time.")
            .sendNext();
        qm.dispose();
        return;
    }
    
    if (mode == 1) {
        status++;
    } else {
        status--;
    }
    
    if (status == 0) {
        qm.createReply()
            .text("Oh, a new traveler! Hey there, my name is #bTodd#k. This is where I teach new travelers like you #bhow to hunt monsters#k. You won't find monsters in Mushroom Town, but if you venture a little bit outside, those places are in fact full of them. It'll be quite dangerous for you to go there, if you aren't well-prepared for it.")
            .sendNext();
    } else if (status == 1) {
        qm.createReply()
            .text("Let's first discuss the #bbasic attack#k. You can do that by pressing the #bCtrl key#k. Approach the monster, but not too close that you'll touch it, then attack with authority. Got it? Do you want to try it right now?")
            .sendYesNo();
    } else if (status == 2) {
        qm.createReply()
            .text("You see the #rJr. Sentinel#k there? Try attacking it. Normally it's a powerful monster, but Grendel the Really Old, the head of all magicians, put a seal on it just for beginners like you, so you'll have no trouble practicing your swings against it.")
            .sendNext();
    } else if (status == 3) {
        qm.createReply()
            .text("Once you successfully hunt the Jr. Sentinel, you'll earn #bJr. Sentinel Shellpiece#k as a drop. To pick up an item, press #bZ#k on top of the item, so try picking up #bJr. Sentinel Shellpiece#k and give it to #bPeter#k. He'll teach you how to use skills to attack monsters.")
            .linebreak().linebreak()
            .text("#fUI/UIWindow.img/QuestIcon/4/0#")
            .linebreak()
            .text("#e#rYou need to kill 10 Jr. Sentinels#k#n")
            .sendAcceptDecline();
    } else if (status == 4) {
        qm.forceStartQuest();
        qm.dispose();
    }
}

function end(mode, type, selection) {
    if (mode == -1) {
        qm.dispose();
        return;
    }
    
    if (mode == 0 && type > 0) {
        qm.dispose();
        return;
    }
    
    if (mode == 1) {
        status++;
    } else {
        status--;
    }
    
    if (status == 0) {
        qm.createReply()
            .text("Seeing you with Jr. Sentinel Shellpiece, I see that Todd has taught you well on hunting. That means you're fairly aware of the basics of hunting... now I'll teach you how to #buse skills#k.")
            .sendNext();
    } else if (status == 1) {
        qm.createReply()
            .text("Skills can be checked out by pressing #bK#k to open the skill window. Unlike regular attacks, skills can only be used by raising the skill level using Skill Points. You can earn Skill Points by leveling up, but you need to be careful when assigning those points to different skills.")
            .sendNextPrev();
    } else if (status == 2) {
        qm.createReply()
            .text("The effectiveness of the skills differ greatly, based on the level. For example, the Three Snail Skill will require a regular Snail shell on Level 1, but on Level 2, you'll need Blue Snail shells. You'll have to really think and calculate which skills will maximize your hunting abilities.")
            .sendNextPrev();
    } else if (status == 3) {
        qm.createReply()
            .text("To use a skill, it'll be much easier for you if you assign the skills on the #bQuick Slot#k at the bottom right of the screen. Just drag the skill icon on the skill window to one of the slots, and you're set to go. It'll be easier for you to just attack regularly at this point, but once you make the job advancement and earn more sophisticated skills, those skills will help you tremendously with everything.")
            .sendNextPrev();
    } else if (status == 4) {
        qm.createReply()
            .text("This covers everything on skills. As a gift, I have just given you an item you can wear on your head, so use it wisely. Go to your item inventory and #bdouble-click on the item#k to equip it immediately. To #bmake sure you're equipped with it,#k press #bE#k. Happy traveling!")
            .sendOk();
    } else if (status == 5) {
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
