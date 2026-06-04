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
 * On villager interaction:
 *   - If current level has no picks yet: send picker, cancel vanilla merchant open
 *   - Else: ensure villager.getOffers() match the picks, allow vanilla flow
 *
 * On picker submit:
 *   - Save picks, regenerate offers, ask player to right-click again
 *
 * On reset:
 *   - Wipe profile, drop villager back to Novice, clear offers
 */
public final class ProfileController {
    private ProfileController() {}

    /**
     * Called when a player right-clicks a villager.
     * @return true if vanilla interaction should proceed; false to cancel.
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
        VillagerProfile profile = state.getOrCreate(villager.getUUID(), profName);

        if (!profile.hasPicksFor(merchantLevel)) {
            // Need picker
            sendPicker(player, villager, profile, merchantLevel);
            return false;
        }

        // Has picks — make sure live offers match
        applyPicksToVillager(level, villager, profile);
        return true;
    }

    /** Called from PickerSubmit network handler. */
    public static void onPickerSubmit(ServerPlayer player, UUID villagerId, int level, List<TradeKey> picks) {
        ServerLevel sl = player.level();
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) {
            player.sendSystemMessage(Component.literal("Villager not found."));
            return;
        }
        VillagerProfile profile = VillagerProfileState.get(sl).getOrCreate(
                villagerId,
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value()).toString()
        );

        profile.setPicks(level, picks);
        VillagerProfileState.get(sl).update(profile);

        applyPicksToVillager(sl, villager, profile);

        player.sendSystemMessage(Component.literal(
                "Trades locked in for level " + level + ". Right-click the villager to trade."));
    }

    /** Called from ResetVillager network handler. */
    public static void onReset(ServerPlayer player, UUID villagerId) {
        ServerLevel sl = player.level();
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) return;

        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);
        if (profile != null) {
            profile.clearAll();
            state.update(profile);
        }

        // Drop level back to Novice, zero XP, clear offers.
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

    private static void sendPicker(ServerPlayer player, Villager villager, VillagerProfile profile, int merchantLevel) {
        ServerLevel level = player.level();
        VillagerProfession prof = villager.getVillagerData().profession().value();
        ResourceKey<TradeSet> tradeSetKey = prof.getTrades(merchantLevel);
        if (tradeSetKey == null) {
            TradeOptimizer.LOGGER.warn("No trade set for {} level {}", profile.profession(), merchantLevel);
            return;
        }

        List<AvailableTrade> available = OfferFactory.enumerate(level, tradeSetKey);
        if (available.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "No trades available for " + profile.profession() + " level " + merchantLevel));
            return;
        }

        OpenPickerS2C payload = new OpenPickerS2C(
                villager.getUUID(),
                profile.profession(),
                merchantLevel,
                2,           // vanilla always rolls 2 trades per level
                available
        );
        if (ServerPlayNetworking.canSend(player, NetworkPayloads.OPEN_PICKER_TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Construct a MerchantOffers list from the player's picks (across every level
     * up to the villager's current level) and apply it to the villager.
     */
    private static void applyPicksToVillager(ServerLevel level, Villager villager, VillagerProfile profile) {
        int currentLevel = villager.getVillagerData().level();
        MerchantOffers offers = new MerchantOffers();

        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            for (TradeKey key : profile.picksFor(lvl)) {
                Optional<MerchantOffer> offer = OfferFactory.generate(level, key);
                offer.ifPresent(offers::add);
            }
        }

        villager.setOffers(offers);
    }

    /**
     * Validation: given a list of picks the client submitted, return only the ones
     * that are actually in this trade set. Prevents a client from sending random keys.
     */
    public static List<TradeKey> filterValid(ServerLevel level, ResourceKey<TradeSet> tradeSetKey, List<TradeKey> picks) {
        List<AvailableTrade> available = OfferFactory.enumerate(level, tradeSetKey);
        List<TradeKey> valid = new ArrayList<>();
        for (TradeKey p : picks) {
            for (AvailableTrade a : available) {
                if (a.key().id().equals(p.id())) {
                    valid.add(p);
                    break;
                }
            }
        }
        return valid;
    }
}
