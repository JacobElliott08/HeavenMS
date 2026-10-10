package server.npc.provider;
import java.util.List;
import server.npc.interaction.NpcInteractionOption;
import server.npc.interaction.NpcInteractionContext;

public interface NpcInteractionProvider {

    List<NpcInteractionOption> provide(NpcInteractionContext context);

}