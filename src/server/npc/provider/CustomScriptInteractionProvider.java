package server.npc.provider;

import server.life.MapleNPC;
import server.npc.interaction.*;
import scripting.npc.NPCScriptManager;
import java.util.*;

/**
 * Provides interactions for special NPC types that require custom script names.
 * Examples: Gachapon NPCs, Maple TV NPCs, and other special cases.
 */
public class CustomScriptInteractionProvider implements NpcInteractionProvider {

    @Override
    public List<NpcInteractionOption> provide(NpcInteractionContext ctx) {
        System.out.println("[TRACE] CustomScriptInteractionProvider.provide() - Checking custom scripts for NPC " + ctx.getNpc().getId());
        MapleNPC npc = ctx.getNpc();
        int npcId = npc.getId();
        String customScript = null;

        // Gachapon NPCs (9100100 - 9100200)
        if (npcId >= 9100100 && npcId <= 9100200) {
            customScript = "gachapon";
        }
        // Maple TV NPCs (by name)
        else if (npc.getName() != null && npc.getName().endsWith("Maple TV")) {
            customScript = "mapleTV";
        }

        if (customScript != null) {
            System.out.println("[TRACE] CustomScriptInteractionProvider.provide() - Found custom script: " + customScript);
            final String scriptName = customScript;
            return List.of(
                new NpcInteractionOption(
                    NpcInteractionType.CUSTOM,
                    "Use " + npc.getName(),
                    context -> {
                        NPCScriptManager.getInstance().start(
                            context.getClient(),
                            npcId,
                            scriptName,
                            context.getPlayer()
                        );
                    }
                )
            );
        }

        System.out.println("[TRACE] CustomScriptInteractionProvider.provide() - No custom script found");
        return List.of();
    }
}
