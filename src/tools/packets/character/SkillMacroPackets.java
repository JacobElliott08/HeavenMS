package tools.packets.character;

import client.SkillMacro;
import client.keybind.MapleKeyBinding;
import com.google.flatbuffers.FlatBufferBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import protocol.generated.maple.character.SkillMacroEntry;
import protocol.generated.maple.character.SkillMacroKeyBinding;
import protocol.generated.maple.character.SkillMacroState;
import protocol.generated.maple.network.Packet;
import protocol.generated.maple.network.ServerMessage;

public final class SkillMacroPackets {
    private SkillMacroPackets() {}

    public static byte[] state(SkillMacro[] macros, Map<Integer, MapleKeyBinding> keymap) {
        FlatBufferBuilder builder = new FlatBufferBuilder(256);
        int[] entries = new int[5];
        for (int position = 0; position < entries.length; position++) {
            SkillMacro macro = macros != null && position < macros.length ? macros[position] : null;
            int name = builder.createString(macro == null || macro.getName() == null ? "" : macro.getName());
            entries[position] = SkillMacroEntry.createSkillMacroEntry(
                    builder,
                    position,
                    name,
                    macro != null && macro.getShout() != 0,
                    macro == null ? 0 : macro.getSkill1(),
                    macro == null ? 0 : macro.getSkill2(),
                    macro == null ? 0 : macro.getSkill3());
        }

        int macrosVector = SkillMacroState.createMacrosVector(builder, entries);
        ArrayList<Integer> bindingOffsets = new ArrayList<>();
        if (keymap != null) {
            for (Map.Entry<Integer, MapleKeyBinding> entry : keymap.entrySet()) {
                MapleKeyBinding binding = entry.getValue();
                if (entry.getKey() >= 0 && entry.getKey() < 90
                        && binding != null && binding.getType() == 8
                        && binding.getAction() >= 0 && binding.getAction() < 5) {
                    bindingOffsets.add(SkillMacroKeyBinding.createSkillMacroKeyBinding(
                            builder, entry.getKey(), binding.getAction()));
                }
            }
        }
        int[] bindings = new int[bindingOffsets.size()];
        for (int i = 0; i < bindings.length; i++) {
            bindings[i] = bindingOffsets.get(i);
        }
        int bindingsVector = SkillMacroState.createBindingsVector(builder, bindings);
        int state = SkillMacroState.createSkillMacroState(builder, macrosVector, bindingsVector);
        Packet.startPacket(builder);
        Packet.addServerMessageType(builder, ServerMessage.protocol_generated_maple_character_SkillMacroState);
        Packet.addServerMessage(builder, state);
        Packet.finishPacketBuffer(builder, Packet.endPacket(builder));
        return builder.sizedByteArray();
    }

    public static boolean applyUpdate(
            protocol.generated.maple.character.SkillMacroUpdate update,
            client.MapleCharacter player) {
        if (update == null || update.macrosLength() != 5 || update.bindingsLength() > 90) {
            return false;
        }

        SkillMacro[] macros = new SkillMacro[5];
        boolean[] seen = new boolean[5];
        Set<Integer> boundKeys = new HashSet<>();
        ArrayList<SkillMacroKeyBinding> bindings = new ArrayList<>();
        for (int i = 0; i < update.macrosLength(); i++) {
            SkillMacroEntry entry = update.macros(i);
            if (entry == null || entry.position() >= macros.length || seen[entry.position()]) {
                return false;
            }

            String name = entry.name();
            if (name == null || name.length() > 32
                    || entry.skill1() < 0 || entry.skill2() < 0 || entry.skill3() < 0) {
                return false;
            }

            int position = entry.position();
            seen[position] = true;
            macros[position] = new SkillMacro(
                    entry.skill1(),
                    entry.skill2(),
                    entry.skill3(),
                    name,
                    entry.shout() ? 1 : 0,
                    position);
        }

        for (boolean received : seen) {
            if (!received) {
                return false;
            }
        }

        for (int i = 0; i < update.bindingsLength(); i++) {
            SkillMacroKeyBinding binding = update.bindings(i);
            if (binding == null || binding.key() >= 90 || binding.macroPosition() >= 5
                    || !boundKeys.add(binding.key())) {
                return false;
            }
            bindings.add(binding);
        }

        for (SkillMacro macro : macros) {
            player.updateMacros(macro.getPosition(), macro);
        }

        player.getKeymap().entrySet().removeIf(
                entry -> entry.getValue() != null && entry.getValue().getType() == 8);
        for (SkillMacroKeyBinding binding : bindings) {
            player.changeKeybinding(
                    binding.key(),
                    new MapleKeyBinding(8, binding.macroPosition()));
        }
        return true;
    }
}
