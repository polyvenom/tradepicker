package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side orchestrator for the picker flow.
 *
 * Three cases on first right-click:
 *   1. Fresh villager (no profile, no existing offers): open picker for level 1.
 *   2. Pre-existing villager with vanilla offers (no profile, has offers): import
 *      those offers as "legacy" per level so the player keeps what they already had.
 *      No picker unless they level up later.
 *   3. Villager with profile: ensure live offers match (picks + legacy combined),
 *      open picker only if the current level has neither picks nor legacy.
 */
public final class ProfileController {
    private ProfileController() {}

    /**
     * @return true if vanilla interaction should proceed; false to cancel and show picker.
     */
    public static boolean onInteract(ServerPlayer player, Villager villager) {
        ServerLevel level = player.level();
        VillagerData data = villager.getVillagerData();
        int merchantLevel = data.level();
        Holder<VillagerProfession> profHolder = data.profession();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profHolder.value()).toString();

        // Nitwits and unemployed villagers can't trade — bail.
        if (profHolder.is(VillagerProfession.NITWIT) || profHolder.is(VillagerProfession.NONE)) {
            return true;
        }

        VillagerProfileState state = VillagerProfileState.get(level);
        VillagerProfile profile = state.get(villager.getUUID());

        // Case 2: pre-existing villager with vanilla offers, no profile.
        // Import existing offers into legacy buckets so the player keeps them.
        if (profile == null) {
            profile = VillagerProfile.fresh(villager.getUUID(), profName);
            MerchantOffers existing = villager.getOffers();
            if (existing != null && !existing.isEmpty()) {
                importExistingOffers(profile, existing, merchantLevel);
                state.update(profile);
                TradeOptimizer.LOGGER.info("Imported {} existing offers from villager {} into legacy",
                        existing.size(), villager.getUUID());
                // They've already got valid offers — just let them trade.
                return true;
            }
            state.update(profile);
        } else if (!profile.profession().equals(profName)) {
            // Profession changed (e.g. workstation switched) — wipe and start over.
            profile.clearAll();
            profile = VillagerProfile.fresh(villager.getUUID(), profName);
            state.update(profile);
        }

        if (!profile.isFilled(merchantLevel)) {
            sendPicker(player, villager, profile, merchantLevel);
            return false;
        }

        // Has entries (picks or legacy) for current level — ensure live offers match.
        applyToVillager(level, villager, profile);
        return true;
    }

    public static void onPickerSubmit(ServerPlayer player, UUID villagerId, int level, List<TradeKey> picks) {
        ServerLevel sl = player.level();
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) {
            player.sendSystemMessage(Component.literal("Villager not found."));
            return;
        }
        String profName = BuiltInRegistries.VILLAGER_PROFESSION
                .getKey(villager.getVillagerData().profession().value()).toString();

        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);
        if (profile == null) profile = VillagerProfile.fresh(villagerId, profName);

        profile.setPicks(level, picks);
        state.update(profile);

        applyToVillager(sl, villager, profile);

        player.sendSystemMessage(Component.literal(
                "Trades locked in for level " + level + ". Right-click the villager to trade."));
    }

    public static void onReset(ServerPlayer player, UUID villagerId) {
        ServerLevel sl = player.level();
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) return;

        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);
        if (profile != null) {
            profile.clearAll();
            state.update(profile);
        }

        VillagerData current = villager.getVillagerData();
        villager.setVillagerData(current.withLevel(1));
        villager.setVillagerXp(0);
        villager.setOffers(new MerchantOffers());

        player.sendSystemMessage(Component.literal(
                "Villager reset to Novice. Right-click to pick new trades."));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Split a flat MerchantOffers list into 2-per-level legacy buckets. */
    private static void importExistingOffers(VillagerProfile profile, MerchantOffers existing, int merchantLevel) {
        // Vanilla generates 2 offers per level, in order: indices 0..1 = level 1, 2..3 = level 2, etc.
        int perLevel = 2;
        for (int lvl = 1; lvl <= merchantLevel; lvl++) {
            int start = (lvl - 1) * perLevel;
            int end = Math.min(existing.size(), start + perLevel);
            if (start >= end) continue;
            List<MerchantOffer> bucket = new ArrayList<>(existing.subList(start, end));
            profile.setLegacy(lvl, bucket);
        }
    }

    private static void sendPicker(ServerPlayer player, Villager villager, VillagerProfile profile, int merchantLevel) {
        ServerLevel level = player.level();
        VillagerProfession prof = villager.getVillagerData().profession().value();
        ResourceKey<TradeSet> tradeSetKey = prof.getTrades(merchantLevel);
        if (tradeSetKey == null) {
            TradeOptimizer.LOGGER.warn("No trade set for {} level {}", profile.profession(), merchantLevel);
            return;
        }

        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSetKey);
        if (available.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "No trades available for " + profile.profession() + " level " + merchantLevel));
            return;
        }

        OpenPickerS2C payload = new OpenPickerS2C(
                villager.getUUID(),
                profile.profession(),
                merchantLevel,
                2,
                available
        );
        if (ServerPlayNetworking.canSend(player, NetworkPayloads.OPEN_PICKER_TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Rebuild the villager's offers from profile state. Picks get fresh min-cost generation;
     * legacy levels keep their imported MerchantOffers verbatim so progress isn't lost.
     */
    private static void applyToVillager(ServerLevel level, Villager villager, VillagerProfile profile) {
        int currentLevel = villager.getVillagerData().level();
        MerchantOffers offers = new MerchantOffers();

        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            // Picks first (newly chosen trades)
            for (TradeKey key : profile.picksFor(lvl)) {
                Optional<MerchantOffer> offer = OfferFactory.generate(level, villager, key);
                offer.ifPresent(offers::add);
            }
            // Then preserved legacy offers
            offers.addAll(profile.legacyFor(lvl));
        }

        villager.setOffers(offers);
    }
}
