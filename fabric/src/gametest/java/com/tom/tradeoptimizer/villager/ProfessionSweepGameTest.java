package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Release sweep: every profession, every villager biome, every merchant level — driven through
 * the same entry points the game uses, with every failure collected so one run reports them all.
 *
 * The other suites each pin one mechanic on one profession (mostly farmer). This one exists so
 * that a change to enumeration, generation, levelling or pricing can't pass by breaking a
 * profession nobody happened to test. It reads professions and biomes from the registries, so a
 * profession or biome Mojang adds is swept automatically.
 *
 * Game tests always run with trade_rebalance on (see OwnershipGateGameTest), so armorer and
 * librarian are swept against that pack's tables rather than the default ones. Every other
 * profession is exactly what players have.
 */
public class ProfessionSweepGameTest {

    private static final int MAX_LEVEL = 5;
    private static final int MAX_REPORTED = 40;

    /**
     * Vanilla's thirteen trading professions. A sweep that finds fewer has quietly stopped
     * sweeping — and would otherwise pass with nothing checked.
     */
    private static final int MIN_PROFESSIONS = 13;

    /**
     * Every card the picker could show must generate into a real offer, and the offer must be the
     * one the card advertised — same items, same counts. A card that generates nothing, or
     * generates something else, is a trade the player can pick and never receive.
     */
    @GameTest(maxTicks = 400)
    public void everyCardInEveryPoolGeneratesWhatItShows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        List<String> problems = new ArrayList<>();
        int cards = 0;

        for (ResourceKey<VillagerProfession> prof : tradingProfessions(level)) {
            for (ResourceKey<VillagerType> type : sortedKeys(registries.lookupOrThrow(Registries.VILLAGER_TYPE).registryKeySet())) {
                Villager villager = spawn(helper, prof, type, 1);
                try {
                    for (int lvl = 1; lvl <= MAX_LEVEL; lvl++) {
                        villager.setVillagerData(villager.getVillagerData().withLevel(lvl));
                        String where = name(prof) + "/" + name(type) + " L" + lvl;

                        ResourceKey<TradeSet> tradeSet =
                                villager.getVillagerData().profession().value().getTrades(lvl);
                        if (tradeSet == null) { problems.add(where + ": no trade set"); continue; }

                        List<AvailableTrade> available;
                        try {
                            available = OfferFactory.enumerate(level, villager, tradeSet);
                        } catch (Exception e) {
                            problems.add(where + ": enumerate threw " + e);
                            continue;
                        }
                        if (available.isEmpty()) { problems.add(where + ": empty pool"); continue; }

                        for (AvailableTrade card : available) {
                            cards++;
                            String at = where + " " + card.key().id();
                            Optional<MerchantOffer> generated;
                            try {
                                generated = OfferFactory.generate(level, villager, card.key(), lvl);
                            } catch (Exception e) {
                                problems.add(at + ": generate threw " + e);
                                continue;
                            }
                            if (generated.isEmpty()) { problems.add(at + ": generates nothing"); continue; }
                            String mismatch = describeMismatch(card.previewOffer(), generated.get());
                            if (mismatch != null) problems.add(at + ": card shows " + mismatch);
                        }
                    }
                } finally {
                    villager.discard();
                }
            }
        }

        int professions = tradingProfessions(level).size();
        if (professions < MIN_PROFESSIONS) problems.add("only " + professions + " trading professions found");
        report(helper, problems, professions + " professions, " + cards + " cards checked");
    }

    /**
     * Each profession taken Novice to Master through the real pick and level-up path, then cured,
     * with the vanilla discount formula checked on every offer it ends up selling.
     *
     * Levels with a choice go through onPickerSubmit, levels with nothing to choose through the
     * auto-progress path in sendPicker — the same split the game makes. That covers the held
     * level-up, the grant on both paths, offer building, and that a master villager can't be
     * pushed past Master.
     */
    @GameTest(maxTicks = 400)
    public void everyProfessionLevelsToMasterAndTakesTheCureDiscount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        VillagerProfileState state = VillagerProfileState.get(level);
        List<String> problems = new ArrayList<>();
        int offersChecked = 0;

        for (ResourceKey<VillagerProfession> prof : tradingProfessions(level)) {
            Villager villager = spawn(helper, prof, VillagerType.PLAINS, 1);
            String who = name(prof);
            try {
                VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(),
                        prof.identifier().toString(), player.getUUID());
                state.update(profile);

                for (int lvl = 1; lvl <= MAX_LEVEL; lvl++) {
                    String where = who + " L" + lvl;
                    if (lvl > 1) {
                        villager.setVillagerXp(VillagerData.getMaxXpPerLevel(lvl - 1));
                        if (!ProfileController.isDueToLevelUp(villager)) {
                            problems.add(where + ": not due after earning the XP for it");
                        }
                    }

                    // The game's own split: no-choice ranks auto-progress, the rest need a submit.
                    ProfileController.sendPicker(player, villager, profile, lvl);
                    profile = state.get(villager.getUUID());
                    if (!profile.isFilled(lvl)) {
                        ResourceKey<TradeSet> tradeSet =
                                villager.getVillagerData().profession().value().getTrades(lvl);
                        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSet);
                        List<TradeKey> picks = new ArrayList<>();
                        for (int i = 0; i < Math.min(2, available.size()); i++) {
                            picks.add(available.get(i).key());
                        }
                        ProfileController.onPickerSubmit(player, villager.getUUID(), lvl, picks);
                        profile = state.get(villager.getUUID());
                    }

                    if (villager.getVillagerData().level() != lvl) {
                        problems.add(where + ": rank is " + villager.getVillagerData().level()
                                + " after picking");
                    }
                    if (!profile.isFilled(lvl)) problems.add(where + ": picks not stored");

                    int expectedOffers = 0;
                    for (int l = 1; l <= lvl; l++) expectedOffers += profile.picksFor(l).size();
                    MerchantOffers offers = villager.getOffers();
                    int actual = offers == null ? 0 : offers.size();
                    if (actual != expectedOffers) {
                        problems.add(where + ": " + actual + " offers for " + expectedOffers + " picks");
                    }
                }

                villager.setVillagerXp(Integer.MAX_VALUE / 2);
                if (ProfileController.isDueToLevelUp(villager)) {
                    problems.add(who + ": a Master villager reports a further level-up");
                }

                // Cure-grade reputation, exactly what curing a zombie villager leaves behind.
                villager.getGossips().add(player.getUUID(), GossipType.MAJOR_POSITIVE, 20);
                villager.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, 25);
                int reputation = villager.getPlayerReputation(player);
                ProfileController.onPickerSubmit(player, villager.getUUID(), MAX_LEVEL,
                        profile.picksFor(MAX_LEVEL));

                for (MerchantOffer offer : villager.getOffers()) {
                    offersChecked++;
                    int expected = -(int) Math.floor(reputation * offer.getPriceMultiplier());
                    if (offer.getSpecialPriceDiff() != expected) {
                        problems.add(who + " " + item(offer.getResult()) + ": cure discount "
                                + offer.getSpecialPriceDiff() + ", vanilla gives " + expected);
                    }
                    if (offer.getCostA().getCount() < 1) {
                        problems.add(who + " " + item(offer.getResult()) + ": costs "
                                + offer.getCostA().getCount() + " after the discount");
                    }
                }
            } catch (Exception e) {
                problems.add(who + ": threw " + e);
            } finally {
                player.closeContainer();
                villager.discard();
            }
        }

        int professions = tradingProfessions(level).size();
        if (professions < MIN_PROFESSIONS) problems.add("only " + professions + " trading professions found");
        if (offersChecked == 0) problems.add("no offers were checked");
        report(helper, problems, professions + " professions, " + offersChecked + " cured offers checked");
    }

    // -------------------------------------------------------------------------

    /** Every registered profession that trades — read from the registry, not a hard-coded list. */
    private static List<ResourceKey<VillagerProfession>> tradingProfessions(ServerLevel level) {
        return sortedKeys(level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).registryKeySet())
                .stream()
                .filter(k -> !k.equals(VillagerProfession.NONE) && !k.equals(VillagerProfession.NITWIT))
                .toList();
    }

    /** Registry order isn't guaranteed; sort so a failure report reads the same every run. */
    private static <T> List<ResourceKey<T>> sortedKeys(java.util.Set<ResourceKey<T>> keys) {
        return keys.stream().sorted(java.util.Comparator.comparing(k -> k.identifier().toString())).toList();
    }

    private static Villager spawn(GameTestHelper helper, ResourceKey<VillagerProfession> prof,
                                  ResourceKey<VillagerType> type, int villagerLevel) {
        var registries = helper.getLevel().registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, type)
                .withProfession(registries, prof)
                .withLevel(villagerLevel));
        return villager;
    }

    /** null when the generated offer is the one the card showed; otherwise what differs. */
    private static String describeMismatch(MerchantOffer shown, MerchantOffer got) {
        if (!same(shown.getBaseCostA(), got.getBaseCostA())) {
            return item(shown.getBaseCostA()) + " as cost, gives " + item(got.getBaseCostA());
        }
        if (!same(shown.getCostB(), got.getCostB())) {
            return item(shown.getCostB()) + " as second cost, gives " + item(got.getCostB());
        }
        if (!same(shown.getResult(), got.getResult())) {
            return item(shown.getResult()) + " as result, gives " + item(got.getResult());
        }
        if (got.getBaseCostA().getCount() < 1 || got.getResult().isEmpty()) {
            return "an offer with no cost or no result";
        }
        return null;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
    }

    private static String name(ResourceKey<?> key) {
        return key.identifier().getPath();
    }

    private static void report(GameTestHelper helper, List<String> problems, String summary) {
        if (problems.isEmpty()) {
            TradeOptimizer.LOGGER.info("[sweep] clean: {}", summary);
            helper.succeed();
            return;
        }
        StringBuilder sb = new StringBuilder(problems.size() + " problem(s), " + summary + ":");
        for (int i = 0; i < Math.min(problems.size(), MAX_REPORTED); i++) {
            sb.append("\n  - ").append(problems.get(i));
        }
        if (problems.size() > MAX_REPORTED) sb.append("\n  ... and ").append(problems.size() - MAX_REPORTED).append(" more");
        helper.fail(sb.toString());
    }
}
