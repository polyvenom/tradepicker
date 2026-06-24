package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.VillagerProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
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

public class VillagerProfileCodecGameTest {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void roundTripsThroughCodec(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        Map<Integer, List<TradeKey>> picks = new HashMap<>();
        picks.put(1, List.of(
                new TradeKey(ResourceLocation.fromNamespaceAndPath("minecraft", "farmer_wheat_for_emerald")),
                new TradeKey(ResourceLocation.fromNamespaceAndPath("tradeoptimizer", "book/minecraft/sharpness"))));
        picks.put(2, List.of(
                new TradeKey(ResourceLocation.fromNamespaceAndPath("minecraft", "farmer_pumpkin_for_emerald"))));

        MerchantOffer offerA = new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        MerchantOffer offerB = new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(Items.BREAD, 6), 12, 1, 0.05f);
        MerchantOffer offerC = new MerchantOffer(new ItemCost(Items.CARROT, 22), new ItemStack(Items.EMERALD, 1), 16, 1, 0.05f);
        Map<Integer, List<MerchantOffer>> legacy = new HashMap<>();
        legacy.put(1, List.of(offerA));
        legacy.put(3, List.of(offerB, offerC));

        VillagerProfile full = new VillagerProfile(id, "minecraft:librarian", Optional.of(owner), picks, legacy);
        assertRoundTrips(helper, ops, full, "full profile");

        Map<Integer, List<TradeKey>> picksOnly = new HashMap<>();
        picksOnly.put(1, List.of(new TradeKey(ResourceLocation.fromNamespaceAndPath("minecraft", "farmer_potato_for_emerald"))));
        VillagerProfile ownerless = new VillagerProfile(
                UUID.randomUUID(), "minecraft:farmer", Optional.empty(), picksOnly, new HashMap<>());
        assertRoundTrips(helper, ops, ownerless, "ownerless profile");
        helper.assertTrue(ownerless.owner().isEmpty(), Component.literal("sanity: the ownerless fixture should have no owner"));

        helper.succeed();
    }

    private static void assertRoundTrips(GameTestHelper helper, RegistryOps<Tag> ops,
                                         VillagerProfile original, String label) {
        Tag encoded = VillagerProfile.CODEC.encodeStart(ops, original).getOrThrow();
        VillagerProfile decoded = VillagerProfile.CODEC.parse(ops, encoded).getOrThrow();

        helper.assertTrue(decoded.id().equals(original.id()), Component.literal(label + ": id did not survive round-trip"));
        helper.assertTrue(decoded.profession().equals(original.profession()),
                Component.literal(label + ": profession did not survive round-trip"));
        helper.assertTrue(decoded.owner().equals(original.owner()),
                Component.literal(label + ": owner did not survive round-trip (got " + decoded.owner() + ")"));

        helper.assertTrue(decoded.picks().equals(original.picks()),
                Component.literal(label + ": picks map did not survive round-trip"));

        helper.assertTrue(decoded.legacy().keySet().equals(original.legacy().keySet()),
                Component.literal(label + ": legacy levels changed during round-trip"));
        for (int lvl : original.legacy().keySet()) {
            List<MerchantOffer> before = original.legacy().get(lvl);
            List<MerchantOffer> after = decoded.legacy().get(lvl);
            helper.assertTrue(after != null && after.size() == before.size(),
                    Component.literal(label + ": legacy level " + lvl + " bucket size changed"));
            for (int i = 0; i < before.size(); i++) {
                helper.assertTrue(sameOffer(before.get(i), after.get(i)),
                        Component.literal(label + ": legacy offer at level " + lvl + " index " + i + " did not survive round-trip"));
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