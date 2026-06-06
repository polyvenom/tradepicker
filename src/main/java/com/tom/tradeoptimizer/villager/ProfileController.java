package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.mixin.VillagerInvoker;
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

import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * Op-equivalent permission probe — GAMEMASTERS is MC 26.1.2's named replacement for
     * the old integer permission level 2 (the threshold for /op'd command access).
     * Cached because it's allocation-free to reuse but slightly verbose to construct.
     */
    private static final Permission OP_PERMISSION =
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(OP_PERMISSION);
    }

    /**
     * Called on every right-click of a villager. Either applies picks to the villager
     * (so vanilla's mob interact will then open the merchant) or sends the picker
     * payload to the client. Return value retained for future use; the listener
     * always returns PASS regardless.
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

        TradeOptimizer.LOGGER.debug("[interact] villager={} prof={} mlevel={} offers={} profile={} thread={}",
                villager.getUUID(), profName, merchantLevel,
                villager.getOffers() == null ? "null" : villager.getOffers().size(),
                profile == null ? "null" : ("picks=" + profile.picks().keySet() + " legacy=" + profile.legacy().keySet()),
                Thread.currentThread().getName());

        // Case 2: pre-existing villager with vanilla offers, no profile.
        // Import existing offers into legacy buckets so the player keeps them.
        if (profile == null) {
            // First interaction claims ownership. Only the owner (or an op) may later
            // reset this villager — stops a passerby from wiping a base's villagers.
            profile = VillagerProfile.fresh(villager.getUUID(), profName, player.getUUID());
            MerchantOffers existing = villager.getOffers();
            if (existing != null && !existing.isEmpty()) {
                importExistingOffers(profile, existing, merchantLevel);
                state.update(profile);
                TradeOptimizer.LOGGER.info("Imported {} existing offers from villager {} into legacy (owner={})",
                        existing.size(), villager.getUUID(), player.getName().getString());
                // They already have offers — open the merchant ourselves to keep
                // every "filled villager" path consistent. Letting vanilla handle it
                // here was the source of the 1-frame / no-open bug since vanilla's
                // mobInteract silently no-ops in our flow.
                applyToVillager(level, villager, profile, player);
                villager.setTradingPlayer(player);
                villager.openTradingScreen(player, villager.getDisplayName(), merchantLevel);
                return false;
            }
            state.update(profile);
        } else if (!profile.profession().equals(profName)) {
            // Profession changed (e.g. workstation switched) — wipe and start over,
            // but keep the existing owner so the villager stays "theirs".
            UUID keepOwner = profile.owner().orElse(player.getUUID());
            profile = VillagerProfile.fresh(villager.getUUID(), profName, keepOwner);
            state.update(profile);
        } else if (profile.owner().isEmpty()) {
            // Grandfathered profile from a save written before ownership existed —
            // claim it for whoever right-clicks first after the upgrade.
            profile = profile.withOwner(player.getUUID());
            state.update(profile);
            TradeOptimizer.LOGGER.info("Claimed ownership of grandfathered villager {} for {}",
                    villager.getUUID(), player.getName().getString());
        }

        if (!profile.isFilled(merchantLevel)) {
            sendPicker(player, villager, profile, merchantLevel);
            return false;
        }

        // Has entries (picks or legacy) for current level — ensure live offers match
        // and open the merchant menu directly. Order matters: setTradingPlayer FIRST,
        // then openTradingScreen. MerchantMenu.stillValid() returns
        // (merchant.getTradingPlayer() == player). If tradingPlayer is null when
        // vanilla validates the container on its next tick, it sends
        // ClientboundContainerClosePacket and the menu disappears after 1 frame.
        // That's exactly what `startTrading` does in vanilla, just spelled out here.
        applyToVillager(level, villager, profile, player);
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), merchantLevel);
        return false;
    }

    public static void onPickerSubmit(ServerPlayer player, UUID villagerId, int level, List<TradeKey> picks) {
        ServerLevel sl = player.level();
        TradeOptimizer.LOGGER.debug("[submit] villager={} level={} picks={}",
                villagerId, level, picks);
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) {
            TradeOptimizer.LOGGER.warn("[submit] villager {} not found in level", villagerId);
            player.sendSystemMessage(Component.literal("Villager not found."));
            return;
        }
        VillagerProfession prof = villager.getVillagerData().profession().value();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof).toString();

        // The picks list arrives from the client and CANNOT be trusted. Without these
        // checks a modified client could submit any villager-trade id (or any synthetic
        // book key for any enchantment/level) and the server would happily build it at
        // minimum cost — letting players hand themselves arbitrary cheap trades.

        // 1) The level must be one this villager has actually reached. The picker only
        //    ever opens for the villager's current level, so a submit for a higher level
        //    is a tampered packet.
        int currentLevel = villager.getVillagerData().level();
        if (level < 1 || level > currentLevel) {
            TradeOptimizer.LOGGER.warn("[submit] rejected out-of-range level {} for villager {} (at level {})",
                    level, villagerId, currentLevel);
            return;
        }

        // 2) Every pick must be a real option in this (profession, level) trade pool.
        //    Re-enumerate the pool server-side and drop anything that isn't in it.
        ResourceKey<TradeSet> tradeSetKey = prof.getTrades(level);
        if (tradeSetKey == null) {
            TradeOptimizer.LOGGER.warn("[submit] no trade set for {} level {}", profName, level);
            return;
        }
        List<AvailableTrade> available;
        try {
            available = OfferFactory.enumerate(sl, villager, tradeSetKey);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("[submit] enumeration failed for {} level {}", profName, level, e);
            return;
        }
        Set<Identifier> validIds = new HashSet<>();
        for (AvailableTrade t : available) validIds.add(t.key().id());

        List<TradeKey> validatedPicks = new ArrayList<>(picks.size());
        for (TradeKey k : picks) {
            if (validIds.contains(k.id())) validatedPicks.add(k);
            else TradeOptimizer.LOGGER.warn("[submit] dropped invalid pick {} for {} level {}",
                    k.id(), profName, level);
        }
        if (validatedPicks.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: those trades aren't valid for this villager."));
            return;
        }

        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);
        // If a profile already exists with a different owner, refuse — only the owner
        // (or an op) may overwrite picks. Without this, a non-owner who somehow reached
        // an unfilled level (e.g. after the villager leveled up while owner was offline)
        // could replace the owner's planned trades.
        if (profile != null && profile.owner().isPresent()
                && !profile.owner().get().equals(player.getUUID())
                && !isOp(player)) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: villager {} owned by {}, not {}",
                    villagerId, profile.owner().get(), player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: this villager belongs to another player."));
            return;
        }
        if (profile == null) {
            // Shouldn't normally happen — picker only opens after onInteract creates a
            // profile. Belt-and-braces: claim ownership now.
            profile = VillagerProfile.fresh(villagerId, profName, player.getUUID());
        } else if (profile.owner().isEmpty()) {
            profile = profile.withOwner(player.getUUID());
        }

        profile.setPicks(level, validatedPicks);
        state.update(profile);

        applyToVillager(sl, villager, profile, player);

        // Auto-open the merchant right here so the user doesn't have to right-click
        // again after confirming picks. Same setTradingPlayer + openTradingScreen
        // pair we use in onInteract.
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), level);
    }

    public static void onReset(ServerPlayer player, UUID villagerId) {
        ServerLevel sl = player.level();
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) return;

        // Reset is destructive (wipes XP, level, and locked-in trades). Lock it to the
        // owner — vanilla already scopes reputation per-player, so per-player ownership
        // for a destructive op fits the same model. Ops bypass for cleanup.
        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);

        boolean op = isOp(player);

        if (profile == null) {
            // No profile means nobody has ever interacted via this mod — reject so a
            // crafted packet can't wipe a stranger's vanilla villager.
            if (!op) {
                TradeOptimizer.LOGGER.warn("[reset] rejected: villager {} has no profile (requested by {})",
                        villagerId, player.getName().getString());
                player.sendSystemMessage(Component.literal(
                        "Trade Picker: this villager hasn't been claimed yet — right-click it first."));
                return;
            }
        } else if (profile.owner().isPresent()
                && !profile.owner().get().equals(player.getUUID()) && !op) {
            TradeOptimizer.LOGGER.warn("[reset] rejected: villager {} owned by {}, not {}",
                    villagerId, profile.owner().get(), player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: this villager belongs to another player."));
            return;
        }

        if (profile != null) {
            // Preserve the owner across reset — they still own the villager, they're
            // just starting their picks over.
            UUID keepOwner = profile.owner().orElse(player.getUUID());
            String profName = profile.profession();
            profile = VillagerProfile.fresh(villagerId, profName, keepOwner);
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

        List<AvailableTrade> available;
        try {
            available = OfferFactory.enumerate(level, villager, tradeSetKey);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("Trade enumeration failed for {} level {}",
                    profile.profession(), merchantLevel, e);
            player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: failed to enumerate trades (see server log)."));
            return;
        }
        if (available.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "No trades available for " + profile.profession() + " level " + merchantLevel));
            return;
        }

        // No-choice fast path. Vanilla always assigns 2 trades per level. When the pool
        // for this level has 2 or fewer options there's nothing to choose: with 2 the
        // player would be forced to take both, and with 1 the picker (which requires 2
        // selections) could never be satisfied — the villager would be stuck and unable
        // to advance. Example: a toolsmith's master level only offers the diamond
        // pickaxe. So skip the picker entirely, apply every available option as the
        // picks, and open the merchant directly. This only ever fires when size <= 2,
        // so a level with 3+ genuine choices always still shows the picker.
        if (available.size() <= 2) {
            List<TradeKey> autoPicks = new ArrayList<>(available.size());
            for (AvailableTrade trade : available) autoPicks.add(trade.key());

            VillagerProfileState state = VillagerProfileState.get(level);
            profile.setPicks(merchantLevel, autoPicks);
            state.update(profile);

            applyToVillager(level, villager, profile, player);
            villager.setTradingPlayer(player);
            villager.openTradingScreen(player, villager.getDisplayName(), merchantLevel);

            TradeOptimizer.LOGGER.info("Auto-progressed {} level {}: {} option(s), no choice needed",
                    profile.profession(), merchantLevel, available.size());
            return;
        }

        TradeOptimizer.LOGGER.info("Picker for {} level {}: {} trade options",
                profile.profession(), merchantLevel, available.size());

        OpenPickerS2C payload = new OpenPickerS2C(
                villager.getUUID(),
                profile.profession(),
                merchantLevel,
                2,
                available
        );
        if (!ServerPlayNetworking.canSend(player, NetworkPayloads.OPEN_PICKER_TYPE)) {
            TradeOptimizer.LOGGER.warn("Client can't receive OPEN_PICKER (mod missing on client?)");
            return;
        }
        try {
            ServerPlayNetworking.send(player, payload);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("Failed to send picker payload ({} trades)",
                    available.size(), e);
            player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: picker send failed (see server log)."));
        }
    }

    /**
     * Rebuild the villager's offers from profile state. Picks get fresh min-cost generation;
     * legacy levels keep their imported MerchantOffers verbatim so progress isn't lost.
     * 
     * Reputation modifiers (from curing or hero of the village) are applied if a player is provided.
     */
    private static void applyToVillager(ServerLevel level, Villager villager, VillagerProfile profile, ServerPlayer player) {
        int currentLevel = villager.getVillagerData().level();
        MerchantOffers offers = new MerchantOffers();

        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            for (TradeKey key : profile.picksFor(lvl)) {
                Optional<MerchantOffer> offer = OfferFactory.generate(level, villager, key, lvl);
                if (offer.isEmpty()) {
                    TradeOptimizer.LOGGER.warn("[apply] generate({}) lvl={} returned EMPTY",
                            key.id(), lvl);
                }
                offer.ifPresent(offers::add);
            }
            offers.addAll(profile.legacyFor(lvl));
        }

        villager.setOffers(offers);

        // Reputation and Hero-of-the-Village discounts are computed by vanilla's
        // Villager.updateSpecialPrices(player), which writes each discount into the
        // offer's specialPriceDiff. Vanilla only calls it from startTrading(); because
        // we open the merchant manually (setTradingPlayer + openTradingScreen, to dodge
        // the 1-frame menu bug) that call was being skipped, so curing / Hero discounts
        // never applied. Reproduce it here, mirroring vanilla's startTrading order.
        //
        // updateSpecialPrices ACCUMULATES (it adds to specialPriceDiff, never resets),
        // so clear each offer first — otherwise re-opening a villager whose legacy
        // offers are reused would stack the discount every time.
        if (player != null) {
            for (MerchantOffer offer : offers) {
                offer.resetSpecialPriceDiff();
            }
            ((VillagerInvoker) villager).tradeoptimizer$updateSpecialPrices(player);
        }
    }
}
