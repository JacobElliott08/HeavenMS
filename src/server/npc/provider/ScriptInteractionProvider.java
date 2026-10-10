package server.npc.provider;

import server.life.MapleNPC;
import server.npc.interaction.*;
import scripting.npc.NPCScriptManager;
import java.util.*;

public class ScriptInteractionProvider implements NpcInteractionProvider {

    @Override
    public List<NpcInteractionOption> provide(NpcInteractionContext ctx) {
        System.out.println("[TRACE] ScriptInteractionProvider.provide() - Checking scripts for NPC " + ctx.getNpc().getId());

        MapleNPC npc = ctx.getNpc();
        int npcId = npc.getId();

        // Check if a script exists for this NPC
        if (!NPCScriptManager.getInstance().isNpcScriptAvailable(ctx.getClient(), String.valueOf(npcId))) {
            System.out.println("[TRACE] ScriptInteractionProvider.provide() - No script available for NPC " + npcId);
            return List.of();
        }
        
        System.out.println("[TRACE] ScriptInteractionProvider.provide() - Script available for NPC " + npcId + ", returning script option");

        return List.of(
            new NpcInteractionOption(
                NpcInteractionType.SCRIPT,
                "Talk",
                context -> {
                    NPCScriptManager.getInstance().start(
                        context.getClient(),
                        npcId,
                        context.getPlayer()
                    );
                }
            )
        );
    }
}