package server.npc.provider;

import client.MapleCharacter;
import server.life.MapleNPC;
import server.npc.interaction.*;
import server.quest.MapleQuest;
import server.quest.QuestService;
import scripting.quest.QuestScriptManager;
import java.util.*;

public class QuestInteractionProvider implements NpcInteractionProvider {

    @Override
    public List<NpcInteractionOption> provide(NpcInteractionContext ctx) {
        System.out.println("[TRACE] QuestInteractionProvider.provide() - Checking quest interactions for NPC " + ctx.getNpc().getId());

        MapleCharacter player = ctx.getPlayer();
        MapleNPC npc = ctx.getNpc();
        int npcId = npc.getId();

        List<NpcInteractionOption> options = new ArrayList<>();

        // Get quests available to start (yellow !)
        List<MapleQuest> startable = QuestService.getInstance()
            .getAvailableQuestsToStart(player, npcId);

        System.out.println("[QuestInteractionProvider] NPC " + npcId + " has " + startable.size() + " quests available to start");
        for (MapleQuest quest : startable) {
            final short questId = quest.getId();
            System.out.println("  [QUEST_AVAILABLE] Quest " + questId + ": " + quest.getName());
            options.add(new NpcInteractionOption(
                NpcInteractionType.QUEST_AVAILABLE,
                quest.getName(),
                context -> {
                    if (!quest.hasScriptRequirement(false)) {
                        System.out.println("[FlatBuffers->QuestStart] Unscripted quest " + questId + " started directly for NPC " + npcId);
                        quest.start(context.getPlayer(), npcId);
                        return;
                    }
                    QuestScriptManager.getInstance().start(
                        context.getClient(),
                        questId,
                        npcId
                    );
                },
                (int) questId
            ));
        }

        // Get quests that can be completed (yellow ?)
        List<MapleQuest> completable = QuestService.getInstance()
            .getAvailableQuestsToComplete(player, npcId);

        System.out.println("[QuestInteractionProvider] NPC " + npcId + " has " + completable.size() + " quests that can be completed");
        for (MapleQuest quest : completable) {
            final short questId = quest.getId();
            System.out.println("  [QUEST_COMPLETE] Quest " + questId + ": " + quest.getName());
            options.add(new NpcInteractionOption(
                NpcInteractionType.QUEST_COMPLETE,
                quest.getName(),
                context -> {
                    if (!quest.hasScriptRequirement(true)) {
                        System.out.println("[FlatBuffers->QuestComplete] Unscripted quest " + questId + " completed directly for NPC " + npcId);
                        quest.complete(context.getPlayer(), npcId);
                        return;
                    }
                    QuestScriptManager.getInstance().end(
                        context.getClient(),
                        questId,
                        npcId
                    );
                },
                (int) questId
            ));
        }

        // Get quests in progress but not yet completable (blue ?)
        List<MapleQuest> inProgress = QuestService.getInstance()
            .getAvailableQuestsInProgress(player, npcId);

        System.out.println("[QuestInteractionProvider] NPC " + npcId + " has " + inProgress.size() + " quests in progress");
        for (MapleQuest quest : inProgress) {
            final short questId = quest.getId();
            System.out.println("  [QUEST_IN_PROGRESS] Quest " + questId + ": " + quest.getName());
            options.add(new NpcInteractionOption(
                NpcInteractionType.QUEST_IN_PROGRESS,
                quest.getName(),
                context -> {
                    // For in-progress quests, show the quest dialogue/progress
                    QuestScriptManager.getInstance().start(
                        context.getClient(),
                        questId,
                        npcId
                    );
                },
                (int) questId
            ));
        }

        return options;
    }
}