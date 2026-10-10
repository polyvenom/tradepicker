package com.tom.tradeoptimizer.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Runs the Fabric game-test classes (shared via the gameTest source set) on NeoForge. Both
 * loaders use vanilla's annotation framework, so the same {@code @GameTest} methods apply; they
 * become {@link TestFunction}s here rather than via RegisterGameTestsEvent because NeoForge would
 * glue the class name onto the Fabric-supplied {@code fabric-gametest-api-v1:empty} template.
 * Every test is pointed at our own all-void {@code tradeoptimizer:empty} structure instead
 * (SNBT staged into the run dir's gameteststructures folder by the build).
 */
@EventBusSubscriber(modid = "tradeoptimizer")
public final class NeoForgeGameTests {
    private NeoForgeGameTests() {}

    // Mirrors "fabric-gametest" in fabric/src/gametest/resources/fabric.mod.json.
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

    private static final String EMPTY_STRUCTURE = "tradeoptimizer:empty";

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        int count = 0;
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
                String name = cls.getSimpleName().toLowerCase(Locale.ROOT) + "." + m.getName().toLowerCase(Locale.ROOT);
                Consumer<GameTestHelper> body = helper -> invoke(m, instance, helper);
                GameTestRegistry.getAllTestFunctions().add(new TestFunction(
                        ann.batch(), name, EMPTY_STRUCTURE,
                        StructureUtils.getRotationForRotationSteps(ann.rotationSteps()),
                        ann.timeoutTicks(), ann.setupTicks(), ann.required(), ann.manualOnly(),
                        ann.requiredSuccesses(), ann.attempts(), ann.skyAccess(), body));
                GameTestRegistry.getAllTestClassNames().add(cls.getSimpleName());
                count++;
            }
        }
        System.out.println("[TradePicker] registered " + count + " NeoForge game tests");
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
}
