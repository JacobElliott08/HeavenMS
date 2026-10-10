package server.npc.provider;
import server.life.MapleNPC;
import server.npc.interaction.*;

import java.util.*;


public class ShopInteractionProvider implements NpcInteractionProvider {

    @Override
    public List<NpcInteractionOption> provide(NpcInteractionContext ctx) {
        System.out.println("[TRACE] ShopInteractionProvider.provide() - Checking shop for NPC " + ctx.getNpc().getId());

        MapleNPC npc = ctx.getNpc();

        if (!npc.hasShop()) {
            System.out.println("[TRACE] ShopInteractionProvider.provide() - NPC has no shop");
            return List.of();
        }
        
        System.out.println("[TRACE] ShopInteractionProvider.provide() - NPC has shop, returning shop option");

        return List.of(
            new NpcInteractionOption(
                NpcInteractionType.SHOP,
                "I want to buy something.",
                context -> npc.sendShop(context.getClient())
            )
        );
    }
}