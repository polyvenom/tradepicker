package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.NeoForgeBookKeyTest;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.NeoForgeLegacyBucketingTest;
import com.tom.tradeoptimizer.villager.ProfileController;
import com.tom.tradeoptimizer.villager.VillagerProfile;
import com.tom.tradeoptimizer.villager.VillagerProfileState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * NeoForge port of the Fabric game-test safety net. NeoForge 26.1.2 uses the data-driven
 * framework, so each test body is a {@code Consumer<GameTestHelper>} registered into the
 * TEST_FUNCTION registry (RegisterEvent) and wrapped in a FunctionGameTestInstance
 * (RegisterGameTestsEvent) using the built-in empty environment and the {@code minecraft:empty}
 * structure. The bodies are the same vanilla GameTestHelper logic as the Fabric tests.
 *
 * The two package-private tests (legacy bucketing, book-key format) live in the matching
 * com.tom.tradeoptimizer.villager / .trade packages so they can reach package-private members;
 * they're registered here.
 */
@EventBusSubscriber(modid = "tradeoptimizer")
public final class NeoForgeGameTests {
    private NeoForgeGameTests() {}

    private static final String NS = "tradeoptimizer";
    private static final Identifier ENV_ID = Identifier.fromNamespaceAndPath(NS, "default");
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");

    private static ResourceKey<Consumer<GameTestHelper>> fnKey(String name) {
        return ResourceKey.create(Registries.TEST_FUNCTION, Identifier.fromNamespaceAndPath(NS, name));
    }

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            helper.register(fnKey("farmer_enumerates"), (Consumer<GameTestHelper>) NeoForgeGameTests::farmerLevelOneEnumeratesTrades);
            helper.register(fnKey("restock_levelup"), (Consumer<GameTestHelper>) NeoForgeGameTests::levelUpDoesNotRestockLowerLevelTrades);
            helper.register(fnKey("restock_normal"), (Consumer<GameTestHelper>) NeoForgeGameTests::normalRestockStillRefillsCarriedOverTrades);
            helper.register(fnKey("owner_pick_reset"), (Consumer<GameTestHelper>) NeoForgeGameTests::ownerCanPickThenReset);
            helper.register(fnKey("non_owner_rejected"), (Consumer<GameTestHelper>) NeoForgeGameTests::nonOwnerIsRejected);
            helper.register(fnKey("op_bypasses"), (Consumer<GameTestHelper>) NeoForgeGameTests::opBypassesGates);
            helper.register(fnKey("codec_roundtrip"), (Consumer<GameTestHelper>) NeoForgeGameTests::roundTripsThroughCodec);
            helper.register(fnKey("price_seed"), (Consumer<GameTestHelper>) NeoForgeGameTests::seededPriceIsStableAndMatchesPreview);
            helper.register(fnKey("legacy_bucketing"), (Consumer<GameTestHelper>) NeoForgeLegacyBucketingTest::bucketsLegacyOffersPerLevel);
            helper.register(fnKey("book_key_format"), (Consumer<GameTestHelper>) NeoForgeBookKeyTest::bookKeyRoundTripsInNewLowercaseFormat);
        });
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env =
                event.registerEnvironment(ENV_ID, new TestEnvironmentDefinition.AllOf(List.of()));
        for (String name : List.of(
                "farmer_enumerates", "restock_levelup", "restock_normal",
                "owner_pick_reset", "non_owner_rejected", "op_bypasses",
                "codec_roundtrip", "price_seed", "legacy_bucketing", "book_key_format")) {
            event.registerTest(Identifier.fromNamespaceAndPath(NS, name),
                    new FunctionGameTestInstance(fnKey(name),
                            new TestData<>(env, EMPTY_STRUCTURE, 200, 0, true)));
        }
    }

    // ============================ shared helpers ============================

    private static Villager spawnFarmer(GameTestHelper helper, int villagerLevel) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager,
                                                int merchantLevel, GameTestHelper helper) {
        ResourceKey<TradeSet> tradeSetKey =
                villager.getVillagerData().profession().value().getTrades(merchantLevel);
        helper.assertTrue(tradeSetKey != null, "farmer level " + merchantLevel + " should have a trade set");
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSetKey);
        helper.assertTrue(available.size() >= 2,
                "farmer level " + merchantLevel + " needs >=2 trade options (got " + available.size() + ")");
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }

    // ============================ test bodies ============================

    static void farmerLevelOneEnumeratesTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        ResourceKey<TradeSet> tradeSetKey = villager.getVillagerData().profession().value().getTrades(1);
        helper.assertTrue(tradeSetKey != null, "farmer level 1 should have a trade set");
        List<AvailableTrade> trades = OfferFactory.enumerate(level, villager, tradeSetKey);
        helper.assertTrue(!trades.isEmpty(), "farmer level 1 enumeration returned no trades");
        for (AvailableTrade t : trades) {
            helper.assertTrue(!t.previewOffer().getResult().isEmpty(),
                    "trade " + t.key().id() + " produced an empty preview result");
        }
        helper.succeed();
    }

    static void levelUpDoesNotRestockLowerLevelTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        ProfileController.onPickerSubmit(player, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        MerchantOffers offers = villager.getOffers();
        helper.assertTrue(offers != null && offers.size() >= 2,
                "expected at least the two picked level-1 offers after submit (got "
                        + (offers == null ? "null" : offers.size()) + ")");

        MerchantOffer level1Offer = offers.get(0);
        int maxUses = level1Offer.getMaxUses();
        helper.assertTrue(maxUses >= 3, "expected the level-1 trade to allow >=3 uses (maxUses=" + maxUses + ")");
        level1Offer.increaseUses();
        level1Offer.increaseUses();
        level1Offer.increaseUses();
        helper.assertTrue(level1Offer.getUses() == 3,
                "sanity: level-1 trade should read 3 uses (got " + level1Offer.getUses() + ")");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ProfileController.onPickerSubmit(player, villagerId, 2, firstTwoPicks(level, villager, 2, helper));

        MerchantOffers after = villager.getOffers();
        helper.assertTrue(after != null && !after.isEmpty(), "villager lost its offers after the level-2 submit");
        int usesAfter = after.get(0).getUses();
        helper.assertTrue(usesAfter == 3,
                "RESTOCK EXPLOIT: level-1 trade use-count reset to " + usesAfter + " (expected 3 preserved)");
        helper.succeed();
    }

    static void normalRestockStillRefillsCarriedOverTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        ProfileController.onPickerSubmit(player, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        MerchantOffers offers = villager.getOffers();
        helper.assertTrue(offers != null && offers.size() >= 2, "expected the two level-1 offers after submit");

        MerchantOffer l1 = offers.get(0);
        int maxUses = l1.getMaxUses();
        while (l1.getUses() < maxUses) l1.increaseUses();
        helper.assertTrue(l1.getUses() == maxUses && l1.isOutOfStock(),
                "precondition: the level-1 trade should be maxed out / out of stock");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ProfileController.onPickerSubmit(player, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertTrue(villager.getOffers().get(0).getUses() == maxUses,
                "carry-over should preserve the maxed use-count across the level-2 pick");

        villager.restock();
        helper.assertTrue(villager.getOffers().get(0).getUses() == 0,
                "normal restock() must clear the carried-over use-count");
        helper.assertTrue(!villager.getOffers().get(0).isOutOfStock(),
                "after restock the level-1 trade should be back in stock");
        helper.succeed();
    }

    static void ownerCanPickThenReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p != null, "owner submit should create a profile");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "owner submit should claim ownership for the submitting player");
        helper.assertTrue(p.picksFor(1).size() == 2, "owner's level-1 picks should be stored");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertTrue(state.get(villagerId).picksFor(2).size() == 2, "owner's level-2 picks should be stored");

        ProfileController.onReset(owner, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 1, "reset should drop the villager to level 1");
        helper.assertTrue(villager.getVillagerXp() == 0, "reset should zero the villager XP");
        helper.assertTrue(villager.getOffers().isEmpty(), "reset should clear the live offers");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(), "reset should wipe all picks");
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                "reset should preserve ownership");
        helper.succeed();
    }

    static void nonOwnerIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(state.get(villagerId).picksFor(1).size() == 2, "precondition: owner's level-1 picks stored");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));

        ServerPlayer intruder = helper.makeMockServerPlayerInLevel();
        ProfileController.onPickerSubmit(intruder, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).isEmpty(), "non-owner submit must NOT store level-2 picks");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "non-owner submit must not change ownership");
        helper.assertTrue(p.picksFor(1).size() == 2, "owner's existing picks must remain intact");

        ProfileController.onReset(intruder, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 2, "non-owner reset must NOT drop the villager level");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID())
                        && after.picksFor(1).size() == 2, "non-owner reset must leave the profile intact");
        helper.succeed();
    }

    static void opBypassesGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));

        villager.setVillagerData(villager.getVillagerData().withLevel(2));

        ServerPlayer op = helper.makeMockServerPlayerInLevel();
        level.getServer().getPlayerList().op(op.nameAndId(),
                Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        helper.assertTrue(
                op.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)),
                "sanity: op() should grant the mock player GAMEMASTERS-level permission");

        ProfileController.onPickerSubmit(op, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).size() == 2, "op submit should be accepted despite not owning the villager");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "op submit should not steal ownership from the original owner");

        ProfileController.onReset(op, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 1, "op reset should drop the villager to level 1");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(), "op reset should wipe picks");
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                "op reset should preserve the original owner");
        helper.succeed();
    }

    static void roundTripsThroughCodec(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
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
        helper.assertTrue(decoded.profession().equals(original.profession()), label + ": profession did not survive round-trip");
        helper.assertTrue(decoded.owner().equals(original.owner()), label + ": owner did not survive round-trip (got " + decoded.owner() + ")");
        helper.assertTrue(decoded.picks().equals(original.picks()), label + ": picks map did not survive round-trip");
        helper.assertTrue(decoded.legacy().keySet().equals(original.legacy().keySet()), label + ": legacy levels changed during round-trip");
        for (int lvl : original.legacy().keySet()) {
            List<MerchantOffer> before = original.legacy().get(lvl);
            List<MerchantOffer> after = decoded.legacy().get(lvl);
            helper.assertTrue(after != null && after.size() == before.size(), label + ": legacy level " + lvl + " bucket size changed");
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

    static void seededPriceIsStableAndMatchesPreview(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        ResourceKey<TradeSet> tradeSetKey = villager.getVillagerData().profession().value().getTrades(1);
        helper.assertTrue(tradeSetKey != null, "farmer level 1 should have a trade set");

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean originalMode = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        try {
            List<AvailableTrade> first = OfferFactory.enumerate(level, villager, tradeSetKey);
            helper.assertTrue(!first.isEmpty(), "farmer level 1 should enumerate at least one trade");
            for (AvailableTrade t : first) {
                TradeKey k = t.key();
                int previewPrice = t.previewOffer().getBaseCostA().getCount();
                Optional<MerchantOffer> o1 = OfferFactory.generate(level, villager, k, 1);
                Optional<MerchantOffer> o2 = OfferFactory.generate(level, villager, k, 1);
                helper.assertTrue(o1.isPresent() && o2.isPresent(), "generate should produce an offer for " + k.id());
                int p1 = o1.get().getBaseCostA().getCount();
                int p2 = o2.get().getBaseCostA().getCount();
                helper.assertTrue(p1 == p2, k.id() + ": seeded price not stable across generate() calls (" + p1 + " vs " + p2 + ")");
                helper.assertTrue(p1 == previewPrice,
                        k.id() + ": applied price " + p1 + " != preview price " + previewPrice + " (reopen-to-reroll guarantee)");
            }
            List<AvailableTrade> second = OfferFactory.enumerate(level, villager, tradeSetKey);
            helper.assertTrue(second.size() == first.size(),
                    "re-enumeration changed the trade count (" + first.size() + " -> " + second.size() + ")");
            for (int i = 0; i < first.size(); i++) {
                int a = first.get(i).previewOffer().getBaseCostA().getCount();
                int b = second.get(i).previewOffer().getBaseCostA().getCount();
                helper.assertTrue(a == b, first.get(i).key().id() + ": preview price changed on reopen (" + a + " -> " + b + ")");
            }
        } finally {
            cfg.setVanillaPricing(originalMode);
        }
        helper.succeed();
    }
}
