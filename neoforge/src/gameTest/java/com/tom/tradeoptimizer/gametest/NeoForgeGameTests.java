package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * NeoForge port of the Fabric game tests. NeoForge 26.1.2 uses the data-driven game-test
 * framework, so each test is a {@code Consumer<GameTestHelper>} registered into the
 * TEST_FUNCTION registry (via RegisterEvent), then wrapped in a FunctionGameTestInstance and
 * registered as a test instance (via RegisterGameTestsEvent) using the built-in empty
 * environment and the {@code minecraft:empty} structure (an empty arena, no NBT needed).
 *
 * The bodies are the same vanilla GameTestHelper logic as the Fabric tests; only the
 * registration differs. Run with: {@code ./gradlew :neoforge:runGameTest}
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
            Consumer<GameTestHelper> farmer = NeoForgeGameTests::farmerLevelOneEnumeratesTrades;
            helper.register(fnKey("farmer_enumerates"), farmer);
        });
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env =
                event.registerEnvironment(ENV_ID, new TestEnvironmentDefinition.AllOf(List.of()));
        event.registerTest(
                Identifier.fromNamespaceAndPath(NS, "farmer_enumerates"),
                new FunctionGameTestInstance(fnKey("farmer_enumerates"),
                        new TestData<>(env, EMPTY_STRUCTURE, 100, 0, true)));
    }

    // ---- test bodies (identical vanilla GameTestHelper logic to the Fabric tests) ----

    static void farmerLevelOneEnumeratesTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));

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
}
