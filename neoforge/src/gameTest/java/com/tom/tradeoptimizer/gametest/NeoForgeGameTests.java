package com.tom.tradeoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Adapter that runs the Fabric game-test classes (shared via the gameTest source set) on NeoForge.
 * NeoForge 26.x uses the data-driven framework, so each {@code @GameTest} method becomes a
 * {@code Consumer<GameTestHelper>} in the TEST_FUNCTION registry (RegisterEvent) wrapped in a
 * FunctionGameTestInstance (RegisterGameTestsEvent) with the built-in empty structure.
 * The class list mirrors fabric/src/gametest/resources/fabric.mod.json.
 */
@EventBusSubscriber(modid = "tradeoptimizer")
public final class NeoForgeGameTests {
    private NeoForgeGameTests() {}

    private static final String NS = "tradeoptimizer";
    private static final Identifier ENV_ID = Identifier.fromNamespaceAndPath(NS, "default");
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");

    // Keep in sync with "fabric-gametest" in fabric/src/gametest/resources/fabric.mod.json.
    private static final List<String> TEST_CLASSES = List.of(
            "com.tom.tradeoptimizer.gametest.OfferFactoryGameTest",
            "com.tom.tradeoptimizer.gametest.RestockExploitGameTest",
            "com.tom.tradeoptimizer.gametest.OwnershipGateGameTest",
            "com.tom.tradeoptimizer.gametest.VillagerProfileCodecGameTest",
            "com.tom.tradeoptimizer.gametest.PriceSeedGameTest",
            "com.tom.tradeoptimizer.villager.LegacyBucketingGameTest",
            "com.tom.tradeoptimizer.villager.ProfilePersistenceGameTest",
            "com.tom.tradeoptimizer.trade.BookKeyFormatGameTest",
            "com.tom.tradeoptimizer.gametest.GearTradeGameTest",
            "com.tom.tradeoptimizer.gametest.ReputationPricingGameTest",
            "com.tom.tradeoptimizer.gametest.ConversionProfileGameTest",
            "com.tom.tradeoptimizer.villager.VanillaInteractionGateGameTest",
            "com.tom.tradeoptimizer.villager.LevelCatchUpGameTest",
            "com.tom.tradeoptimizer.villager.LevelUpHoldGameTest",
            "com.tom.tradeoptimizer.villager.ProfessionSweepGameTest",
            "com.tom.tradeoptimizer.villager.TickedLevelUpGameTest",
            "com.tom.tradeoptimizer.villager.ActionGateGameTest",
            "com.tom.tradeoptimizer.villager.PoolCacheGameTest",
            "com.tom.tradeoptimizer.gametest.BookPricingGameTest",
            "com.tom.tradeoptimizer.gametest.TradeOrderGameTest",
            "com.tom.tradeoptimizer.gametest.VariantTradeGameTest");

    private record Case(String name, int maxTicks, Consumer<GameTestHelper> body) {}

    private static List<Case> discover() {
        List<Case> cases = new ArrayList<>();
        for (String className : TEST_CLASSES) {
            Class<?> cls;
            Object instance;
            try {
                cls = Class.forName(className);
                var ctor = cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                instance = ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot load game test class " + className, e);
            }
            for (Method m : cls.getDeclaredMethods()) {
                GameTest ann = m.getAnnotation(GameTest.class);
                if (ann == null) continue;
                m.setAccessible(true);
                String name = (cls.getSimpleName() + "." + m.getName()).toLowerCase(Locale.ROOT);
                final Object target = instance;
                cases.add(new Case(name, ann.maxTicks(), helper -> invoke(m, target, helper)));
            }
        }
        return cases;
    }

    private static void invoke(Method m, Object target, GameTestHelper helper) {
        try {
            m.invoke(target, helper);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResourceKey<Consumer<GameTestHelper>> fnKey(String name) {
        return ResourceKey.create(Registries.TEST_FUNCTION, Identifier.fromNamespaceAndPath(NS, name));
    }

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            for (Case c : discover()) {
                helper.register(fnKey(c.name()), c.body());
            }
        });
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env =
                event.registerEnvironment(ENV_ID, new TestEnvironmentDefinition.AllOf(List.of()));
        List<Case> cases = discover();
        for (Case c : cases) {
            event.registerTest(Identifier.fromNamespaceAndPath(NS, c.name()),
                    new FunctionGameTestInstance(fnKey(c.name()),
                            new TestData<>(env, EMPTY_STRUCTURE, c.maxTicks(), 0, true)));
        }
        System.out.println("[TradePicker] registered " + cases.size() + " NeoForge game tests");
    }
}
