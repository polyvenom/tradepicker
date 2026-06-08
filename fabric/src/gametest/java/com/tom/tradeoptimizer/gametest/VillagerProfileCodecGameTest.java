package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.VillagerProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Guards that {@link VillagerProfile#CODEC} round-trips losslessly — the property every saved
 * world depends on. If this breaks, players' picked trades and ownership silently vanish on
 * reload, so it's a high-value guard to lock in before the multi-loader port reshapes anything.
 *
 * Encodes through a real registry serialization context (NBT), because the {@code legacy} lane
 * holds {@link MerchantOffer}s whose codec needs registry access. Covers the optional {@code
 * owner} field (present AND absent) and the int-keyed {@code picks} / {@code legacy} maps.
 */
public class VillagerProfileCodecGameTest {

    @GameTest
    public void roundTripsThroughCodec(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        // 1) A fully-populated profile: owner present, picks at two levels (including a synthetic
        //    book key), legacy offers at two non-contiguous levels.
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        // NOTE: the synthetic book key uses the legacy lowercase form (no "/L<n>" suffix). The
        // headless gametest server validates Identifiers more strictly than the live game and
        // rejects the uppercase 'L' that real per-level book keys carry (audit #5: that key works
        // in the running game, it's only headless MC that's strict). The codec stores Identifier
        // strings verbatim, so case doesn't change what this exercises — round-tripping a book-style
        // pick. Don't reintroduce an uppercase-L key here; it can't be constructed in a gametest.
        Map<Integer, List<TradeKey>> picks = new HashMap<>();
        picks.put(1, List.of(
                new TradeKey(Identifier.fromNamespaceAndPath("minecraft", "farmer_wheat_for_emerald")),
                new TradeKey(Identifier.fromNamespaceAndPath("tradeoptimizer", "book/minecraft/sharpness"))));
        picks.put(2, List.of(
                new TradeKey(Identifier.fromNamespaceAndPath("minecraft", "farmer_pumpkin_for_emerald"))));

        MerchantOffer offerA = new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        MerchantOffer offerB = new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(Items.BREAD, 6), 12, 1, 0.05f);
        MerchantOffer offerC = new MerchantOffer(new ItemCost(Items.CARROT, 22), new ItemStack(Items.EMERALD, 1), 16, 1, 0.05f);
        Map<Integer, List<MerchantOffer>> legacy = new HashMap<>();
        legacy.put(1, List.of(offerA));
        legacy.put(3, List.of(offerB, offerC));

        VillagerProfile full = new VillagerProfile(id, "minecraft:librarian", Optional.of(owner), picks, legacy);
        assertRoundTrips(helper, ops, full, "full profile");

        // 2) A grandfathered profile with NO owner and no legacy — exercises the optional/empty
        //    field paths so an ownerless save still decodes to an empty owner (not a crash).
        Map<Integer, List<TradeKey>> picksOnly = new HashMap<>();
        picksOnly.put(1, List.of(new TradeKey(Identifier.fromNamespaceAndPath("minecraft", "farmer_potato_for_emerald"))));
        VillagerProfile ownerless = new VillagerProfile(
                UUID.randomUUID(), "minecraft:farmer", Optional.empty(), picksOnly, new HashMap<>());
        assertRoundTrips(helper, ops, ownerless, "ownerless profile");
        helper.assertTrue(ownerless.owner().isEmpty(), "sanity: the ownerless fixture should have no owner");

        helper.succeed();
    }

    private static void assertRoundTrips(GameTestHelper helper, RegistryOps<Tag> ops,
                                         VillagerProfile original, String label) {
        Tag encoded = VillagerProfile.CODEC.encodeStart(ops, original).getOrThrow();
        VillagerProfile decoded = VillagerProfile.CODEC.parse(ops, encoded).getOrThrow();

        helper.assertTrue(decoded.id().equals(original.id()), label + ": id did not survive round-trip");
        helper.assertTrue(decoded.profession().equals(original.profession()),
                label + ": profession did not survive round-trip");
        helper.assertTrue(decoded.owner().equals(original.owner()),
                label + ": owner did not survive round-trip (got " + decoded.owner() + ")");

        // TradeKey is a record over Identifier, so picks compare by value directly.
        helper.assertTrue(decoded.picks().equals(original.picks()),
                label + ": picks map did not survive round-trip");

        // MerchantOffer has no value equals, so compare the legacy buckets by content.
        helper.assertTrue(decoded.legacy().keySet().equals(original.legacy().keySet()),
                label + ": legacy levels changed during round-trip");
        for (int lvl : original.legacy().keySet()) {
            List<MerchantOffer> before = original.legacy().get(lvl);
            List<MerchantOffer> after = decoded.legacy().get(lvl);
            helper.assertTrue(after != null && after.size() == before.size(),
                    label + ": legacy level " + lvl + " bucket size changed");
            for (int i = 0; i < before.size(); i++) {
                helper.assertTrue(sameOffer(before.get(i), after.get(i)),
                        label + ": legacy offer at level " + lvl + " index " + i + " did not survive round-trip");
            }
        }
    }

    private static boolean sameOffer(MerchantOffer a, MerchantOffer b) {
        return a.getResult().getItem() == b.getResult().getItem()
                && a.getResult().getCount() == b.getResult().getCount()
                && a.getBaseCostA().getItem() == b.getBaseCostA().getItem()
                && a.getBaseCostA().getCount() == b.getBaseCostA().getCount()
                && a.getMaxUses() == b.getMaxUses()
                && a.getXp() == b.getXp();
    }
}
