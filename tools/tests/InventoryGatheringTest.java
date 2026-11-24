package net.server.channel.handlers;

import client.inventory.Item;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.ModifyInventory;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Offline fixture; no WZ, database, sockets, or timers.
 * Compile against target/classes and Maven's dependency classpath, then run
 * net.server.channel.handlers.InventoryGatheringTest.
 */
public final class InventoryGatheringTest {
    private static final int POTION = 2000000;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Item item(int id, int position, int quantity) {
        return new Item(id, (short) position, (short) quantity);
    }

    private static MapleInventory inventory(MapleInventoryType type, int slots, Item... items) {
        MapleInventory inventory = new MapleInventory(null, type, (byte) slots);
        for (Item item : items) {
            inventory.addItemFromDB(item);
        }
        return inventory;
    }

    private static void merge(MapleInventory inventory, MapleInventoryType type) {
        InventoryGathering.mergeStacks(inventory, type, id -> 20,
                (source, destination) -> inventory.move(source, destination, (short) 20));
    }

    private static void mergesToServerMaximumAndCompacts() {
        MapleInventory inventory = inventory(MapleInventoryType.USE, 10,
                item(POTION, 2, 10), item(POTION, 4, 10), item(POTION, 6, 10),
                item(POTION, 8, 10), item(POTION, 10, 10));
        merge(inventory, MapleInventoryType.USE);
        InventoryGathering.compactAndSort(inventory, id -> 0, id -> "Potion");
        check(inventory.getItem((short) 1).getQuantity() == 20, "First stack must be full");
        check(inventory.getItem((short) 2).getQuantity() == 20, "Second stack must be full");
        check(inventory.getItem((short) 3).getQuantity() == 10, "Remainder must be retained");
        for (short slot = 4; slot <= 10; slot++) {
            check(inventory.getItem(slot) == null, "Compaction must leave only trailing gaps");
        }
    }

    private static void rejectsIncompatibleMetadata() throws ReflectiveOperationException {
        Item base = item(POTION, 1, 5);
        Item owner = item(POTION, 2, 5);
        owner.setOwner("Another owner");
        Item expiration = item(POTION, 3, 5);
        expiration.setExpiration(123456789L);
        Item flag = item(POTION, 4, 5);
        Field flags = Item.class.getDeclaredField("flag");
        flags.setAccessible(true);
        flags.setShort(flag, (short) 8);
        Item gift = item(POTION, 5, 5);
        gift.setGiftFrom("Sender");
        Item serial = item(POTION, 6, 5);
        serial.setSN(99);
        MapleInventory inventory = inventory(MapleInventoryType.USE, 6,
                base, owner, expiration, flag, gift, serial);
        merge(inventory, MapleInventoryType.USE);
        for (short slot = 1; slot <= 6; slot++) {
            check(inventory.getItem(slot).getQuantity() == 5, "Metadata must prevent merging");
        }
        check(!InventoryGathering.canMerge(MapleInventoryType.EQUIP, base, base),
                "Equipment cannot merge");
        check(!InventoryGathering.canMerge(MapleInventoryType.CASH, base, base),
                "Cash identities cannot merge");
        check(!InventoryGathering.canMerge(MapleInventoryType.USE,
                item(2070000, 1, 5), item(2070000, 2, 5)), "Stars cannot merge");
        check(!InventoryGathering.canMerge(MapleInventoryType.USE,
                item(2330000, 1, 5), item(2330000, 2, 5)), "Bullets cannot merge");
    }

    private static void sortsFullInventoryByRequiredLevelThenName() {
        Item high = item(2000001, 1, 1);
        Item zebra = item(2000002, 2, 1);
        Item alpha = item(2000003, 3, 1);
        Item alphaTie = item(2000004, 4, 1);
        Item unknown = item(2000005, 5, 1);
        high.setSN(42);
        high.setGiftFrom("Identity");
        int cashId = high.getCashId();
        MapleInventory inventory = inventory(MapleInventoryType.USE, 5,
                high, zebra, alpha, alphaTie, unknown);
        Map<Integer, String> names = Map.of(2000001, "Aardvark", 2000002, "Zebra",
                2000003, "alpha", 2000004, "ALPHA");
        List<ModifyInventory> modifications = InventoryGathering.compactAndSort(inventory,
                id -> id == high.getItemId() ? 50 : 10, names::get);
        Item[] expected = {alpha, alphaTie, zebra, unknown, high};
        for (short slot = 1; slot <= expected.length; slot++) {
            check(inventory.getItem(slot) == expected[slot - 1],
                    "Sort must preserve original objects and use level/name/ID precedence");
            check(expected[slot - 1].getPosition() == slot, "Position must match slot");
        }
        check(high.getSN() == 42 && high.getGiftFrom().equals("Identity")
                && high.getCashId() == cashId, "Reordering must preserve identity metadata");
        check(!modifications.isEmpty(), "Reordering must notify the client");
        for (ModifyInventory modification : modifications) {
            check(modification.getMode() == 2, "Reordering must send identity-preserving swaps");
        }
        check(InventoryGathering.compactAndSort(inventory,
                id -> id == high.getItemId() ? 50 : 10, names::get).isEmpty(),
                "Repeated Gather must be stable");
    }

    private static void sortingNeverAccidentallyMergesStacks() {
        Item first = item(POTION, 1, 3);
        Item second = item(POTION, 2, 7);
        second.setExpiration(123456789L);
        Item alphabeticallyFirst = item(POTION + 1, 3, 1);
        MapleInventory inventory = inventory(MapleInventoryType.USE, 3,
                first, second, alphabeticallyFirst);
        InventoryGathering.compactAndSort(inventory, id -> 0,
                id -> id == POTION ? "Zebra" : "Alpha");
        check(inventory.getItem((short) 1) == alphabeticallyFirst, "Name order must be applied");
        check(inventory.getItem((short) 2) == first && inventory.getItem((short) 3) == second,
                "Same-ID stacks must retain stable order even when swaps displace them");
        check(first.getQuantity() == 3 && second.getQuantity() == 7,
                "Sorting must not merge incompatible stacks");
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        mergesToServerMaximumAndCompacts();
        rejectsIncompatibleMetadata();
        sortsFullInventoryByRequiredLevelThenName();
        sortingNeverAccidentallyMergesStacks();
        check(InventoryGathering.compactAndSort(
                inventory(MapleInventoryType.USE, 4), id -> 0, id -> null).isEmpty(),
                "Empty inventory must be a no-op");
        System.out.println("InventoryGatheringTest passed");
    }
}
