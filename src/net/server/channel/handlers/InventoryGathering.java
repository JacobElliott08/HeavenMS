package net.server.channel.handlers;

import client.inventory.Item;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.ModifyInventory;
import constants.inventory.ItemConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

final class InventoryGathering {
    private InventoryGathering() {
    }

    static boolean canMerge(MapleInventoryType type, Item source, Item target) {
        return type != MapleInventoryType.EQUIP && type != MapleInventoryType.CASH
                && source.getItemType() == 2 && target.getItemType() == 2
                && source.getItemId() == target.getItemId()
                && !ItemConstants.isRechargeable(source.getItemId())
                && source.getPetId() == -1 && target.getPetId() == -1
                && source.getSN() == 0 && target.getSN() == 0
                && source.getOwner() != null
                && Objects.equals(source.getOwner(), target.getOwner())
                && source.getExpiration() == target.getExpiration()
                && source.getFlag() == target.getFlag()
                && Objects.equals(source.getGiftFrom(), target.getGiftFrom());
    }

    static void mergeStacks(MapleInventory inventory, MapleInventoryType type,
            IntUnaryOperator slotMax, BiConsumer<Short, Short> move) {
        for (short dst = 1; dst <= inventory.getSlotLimit(); dst++) {
            Item target = inventory.getItem(dst);
            if (target == null) {
                continue;
            }
            int maximum = slotMax.applyAsInt(target.getItemId());
            for (short src = (short) (dst + 1);
                    src <= inventory.getSlotLimit() && target.getQuantity() < maximum; src++) {
                Item source = inventory.getItem(src);
                if (source != null && canMerge(type, source, target)) {
                    move.accept(src, dst);
                }
            }
        }
    }

    static List<ModifyInventory> compactAndSort(MapleInventory inventory,
            IntUnaryOperator requiredLevel, IntFunction<String> itemName) {
        List<Item> items = new ArrayList<>();
        for (short slot = 1; slot <= inventory.getSlotLimit(); slot++) {
            Item item = inventory.getItem(slot);
            if (item != null) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparingInt((Item item) -> requiredLevel.applyAsInt(item.getItemId()))
                .thenComparing(item -> itemName.apply(item.getItemId()),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparingInt(Item::getItemId));

        List<ModifyInventory> modifications = new ArrayList<>();
        short destination = 1;
        for (Item item : items) {
            short source = item.getPosition();
            if (source != destination) {
                Item displaced = inventory.getItem(destination);
                // Ordinary moves may merge same-ID stacks, even with different expiry/flags.
                // Reposition the original objects and send a swap, without copying their identity.
                inventory.removeSlot(source);
                inventory.removeSlot(destination);
                item.setPosition(destination);
                inventory.addItemFromDB(item);
                if (displaced != null) {
                    displaced.setPosition(source);
                    inventory.addItemFromDB(displaced);
                }
                modifications.add(new ModifyInventory(2, item, source));
            }
            destination++;
        }
        return modifications;
    }
}
