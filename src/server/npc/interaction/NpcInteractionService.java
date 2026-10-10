package server.npc.interaction;

import java.util.*;
import server.npc.provider.*;
import client.MapleClient;
import server.life.MapleNPC;

/**
 * Central service for discovering available NPC interactions.
 * Coordinates multiple providers to determine what interactions are available for a given NPC.
 */
public class NpcInteractionService {

    private static final NpcInteractionService instance = new NpcInteractionService();

    public static NpcInteractionService getInstance() {
        return instance;
    }

    private final List<NpcInteractionProvider> providers;

    private NpcInteractionService() {

        providers = List.of(
            new CustomScriptInteractionProvider(),
            new QuestInteractionProvider(),
            new ShopInteractionProvider(),
            new ScriptInteractionProvider()
        );

    }

    public List<NpcInteractionOption> getInteractions(
        MapleClient client,
        MapleNPC npc
    ) {
        System.out.println("[TRACE] NPCInteractionService.getInteractions() - Discovering interactions for NPC " + npc.getId() + " (" + npc.getName() + ")");

        NpcInteractionContext context = new NpcInteractionContext(client, npc);

        List<NpcInteractionOption> options = new ArrayList<>();

        for (NpcInteractionProvider provider : providers) {
            System.out.println("[TRACE] NPCInteractionService.getInteractions() - Calling provider: " + provider.getClass().getSimpleName());

            List<NpcInteractionOption> result = provider.provide(context);

            if (result != null && !result.isEmpty()) {
                System.out.println("[TRACE] NPCInteractionService.getInteractions() - Provider " + provider.getClass().getSimpleName() + " returned " + result.size() + " option(s)");
                options.addAll(result);
            } else {
                System.out.println("[TRACE] NPCInteractionService.getInteractions() - Provider " + provider.getClass().getSimpleName() + " returned no options");
            }
        }

        System.out.println("[TRACE] NPCInteractionService.getInteractions() - Total interactions found: " + options.size());
        return options;
    }
}