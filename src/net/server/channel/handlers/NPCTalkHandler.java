package net.server.channel.handlers;

import client.MapleClient;
import client.processor.npc.DueyProcessor;
import config.YamlConfig;
import net.AbstractMaplePacketHandler;
import scripting.npc.NPCScriptManager;
import scripting.npc.ReplyBuilder;
import server.life.MapleNPC;
import server.maps.MapleMapObject;
import server.life.MaplePlayerNPC;
import server.npc.interaction.NpcInteractionService;
import server.npc.interaction.NpcInteractionOption;
import server.npc.interaction.NpcInteractionContext;
import server.npc.session.NpcSession;
import tools.FilePrinter;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;

import java.util.List;

public final class NPCTalkHandler extends AbstractMaplePacketHandler {
    @Override
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Received NPC_TALK packet (0x3A) from player: " + c.getPlayer().getName());
        
        if (!c.getPlayer().isAlive()) {
            System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Player is dead, rejecting interaction");
            c.announce(MaplePacketCreator.enableActions());
            return;
        }
        
        // NPC cooldown check to prevent race conditions
        if (currentServerTime() - c.getPlayer().getNpcCooldown() < YamlConfig.config.server.BLOCK_NPC_RACE_CONDT) {
            System.out.println("[TRACE] NPCTalkHandler.handlePacket() - NPC cooldown active, rejecting interaction");
            c.announce(MaplePacketCreator.enableActions());
            return;
        }
        
        // Prevent double interactions
        if (c.getCM() != null || c.getQM() != null) {
            System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Player already in conversation (CM or QM active), rejecting");
            c.announce(MaplePacketCreator.enableActions());
            return;
        }
        
        int oid = slea.readInt();
        System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Reading object ID from packet: " + oid);
        MapleMapObject obj = c.getPlayer().getMap().getMapObject(oid);
        
        if (obj instanceof MapleNPC) {
            System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Object is MapleNPC, delegating to handleNpcInteraction()");
            handleNpcInteraction(c, (MapleNPC) obj, oid);
        } else if (obj instanceof MaplePlayerNPC) {
            System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Object is MaplePlayerNPC, delegating to handlePlayerNpcInteraction()");
            handlePlayerNpcInteraction(c, (MaplePlayerNPC) obj);
        } else {
            System.out.println("[TRACE] NPCTalkHandler.handlePacket() - Object is not an NPC (type: " + (obj != null ? obj.getClass().getSimpleName() : "null") + "), ignoring");
        }
    }

    /**
     * Handle interaction with a regular NPC using the new provider system.
     */
    private void handleNpcInteraction(MapleClient c, MapleNPC npc, int oid) {
        System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - Starting interaction with NPC ID: " + npc.getId() + " (" + npc.getName() + "), OID: " + oid);
        
        if (YamlConfig.config.server.USE_DEBUG) {
            c.getPlayer().dropMessage(5, "Talking to NPC " + npc.getId());
        }

        // Special case: Duey parcel service
        if (npc.getId() == 9010009) {
            System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - Special case: Duey parcel service, delegating to DueyProcessor");
            DueyProcessor.dueySendTalk(c, false);
            return;
        }

        // Use the new interaction service to discover available interactions
        System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - Calling NpcInteractionService.getInteractions()");
        List<NpcInteractionOption> interactions = 
            NpcInteractionService.getInstance().getInteractions(c, npc);

        System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - Found " + interactions.size() + " interaction(s)");
        
        if (interactions.isEmpty()) {
            // No interactions available
            System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - No interactions available, sending enableActions");
            FilePrinter.printError(FilePrinter.NPC_UNCODED, 
                "NPC " + npc.getName() + "(" + npc.getId() + ") has no available interactions.");
            c.announce(MaplePacketCreator.enableActions());
            return;
        }

        if (interactions.size() == 1) {
            // Execute single interaction immediately
            System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - Single interaction available, executing immediately: " + interactions.get(0).getLabel());
            NpcInteractionContext context = new NpcInteractionContext(c, npc);
            interactions.get(0).execute(context);
            return;
        }
        
        // Multiple interactions available - show menu using new protocol
        System.out.println("[TRACE] NPCTalkHandler.handleNpcInteraction() - Multiple interactions available, creating NpcSession and sending menu");
        NpcSession session = new NpcSession(npc.getId(), oid);
        session.setPendingOptions(interactions);
        c.getPlayer().setNpcSession(session);

        sendInteractionMenu(c, npc, interactions);
    }

    /**
     * Send a menu of interaction options to the client using the new ReplyBuilder protocol.
     * Uses quest-specific element types for granular client-side rendering.
     */
    private void sendInteractionMenu(MapleClient c, MapleNPC npc, List<NpcInteractionOption> interactions) {
        System.out.println("[TRACE] NPCTalkHandler.sendInteractionMenu() - Building interaction menu for NPC " + npc.getId() + " with " + interactions.size() + " options");
        ReplyBuilder reply = ReplyBuilder.acquire(c, npc.getId());
        
        reply.text("What would you like to do?");
        
        int questAvailableCount = 0;
        int questCompleteCount = 0;
        int questInProgressCount = 0;
        int otherCount = 0;
        
        for (NpcInteractionOption interaction : interactions) {
            switch (interaction.getType()) {
                case QUEST_AVAILABLE:
                    // Yellow ! icon - quest available to start
                    if (interaction.hasQuestId()) {
                        reply.questAvailable(interaction.getQuestId(), interaction.getLabel());
                        questAvailableCount++;
                    } else {
                        reply.selection(interaction.getLabel());
                        otherCount++;
                    }
                    break;
                    
                case QUEST_COMPLETE:
                    // Yellow ? icon - quest can be completed
                    if (interaction.hasQuestId()) {
                        reply.questComplete(interaction.getQuestId(), interaction.getLabel());
                        questCompleteCount++;
                    } else {
                        reply.selection(interaction.getLabel());
                        otherCount++;
                    }
                    break;
                    
                case QUEST_IN_PROGRESS:
                    // Blue ? icon - quest in progress but not completable
                    if (interaction.hasQuestId()) {
                        reply.questInProgress(interaction.getQuestId(), interaction.getLabel());
                        questInProgressCount++;
                    } else {
                        reply.selection(interaction.getLabel());
                        otherCount++;
                    }
                    break;
                    
                default:
                    // Standard selection for non-quest interactions
                    reply.selection(interaction.getLabel());
                    otherCount++;
                    break;
            }
        }
        
        System.out.println("[TRACE] NPCTalkHandler.sendInteractionMenu() - Sending interaction menu for NPC " + npc.getId() + 
            " - Available: " + questAvailableCount + 
            ", Complete: " + questCompleteCount + 
            ", InProgress: " + questInProgressCount + 
            ", Other: " + otherCount);
        
        reply.send();
        System.out.println("[TRACE] NPCTalkHandler.sendInteractionMenu() - Menu packet sent to client");
    }

    /**
     * Handle interaction with a player-owned NPC.
     */
    private void handlePlayerNpcInteraction(MapleClient c, MaplePlayerNPC pnpc) {
        System.out.println("[TRACE] NPCTalkHandler.handlePlayerNpcInteraction() - Handling PlayerNPC with script ID: " + pnpc.getScriptId());
        NPCScriptManager nsm = NPCScriptManager.getInstance();
        
        if (pnpc.getScriptId() < 9977777 && !nsm.isNpcScriptAvailable(c, "" + pnpc.getScriptId())) {
            nsm.start(c, pnpc.getScriptId(), "rank_user", null);
        } else {
            nsm.start(c, pnpc.getScriptId(), null);
        }
    }
}