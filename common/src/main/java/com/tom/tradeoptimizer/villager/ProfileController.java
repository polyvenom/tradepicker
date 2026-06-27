package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.api.TradePickerApi;
import com.tom.tradeoptimizer.api.VillagerInteractionHandler;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.platform.Services;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private static boolean isOp(ServerPlayer player) {
        // Integer permission level 2 = the /op command threshold (1.21.1's permission API;
        // the named PermissionLevel constants only exist on the 26.x line).
        return player.hasPermissions(2);
    }

    /**
     * Players whose client lacks the mod, who we've already nudged once. Stops the
     * fallback notice from spamming on every right-click. Lives for the server's lifetime
     * (one UUID per such player — negligible).
     */
    private static final Set<UUID> warnedNoModClients = ConcurrentHashMap.newKeySet();

    /**
     * Called on every right-click of a villager. Either opens the merchant menu directly
     * (after making sure the offers are in place) or sends the picker payload to the
     * client.
     *
     * Returns true only when there was nothing for us to do (nitwit / unemployed villager,
     * or a client without the mod) so the listener can PASS to vanilla. Returns false when
     * we handled the interaction (picker sent, or merchant opened by us) so the listener
     * returns SUCCESS.
     */
    public static boolean onInteract(ServerPlayer player, Villager villager) {
        ServerLevel level = player.level();
        VillagerData data = villager.getVillagerData();
        // 1.21.5: VillagerData is a record; getLevel()/getProfession() → level()/profession().
        int merchantLevel = data.level();
        VillagerProfession prof = data.profession().value();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getResourceKey(prof)
                .map(k -> k.location().toString()).orElse("minecraft:none");

        // Nitwits and unemployed villagers can't trade — bail.
        // 1.21.5: VillagerProfession.NITWIT/NONE are ResourceKey constants; compare via registry key.
        boolean isNitwit = BuiltInRegistries.VILLAGER_PROFESSION.getResourceKey(prof)
                .map(k -> k.equals(VillagerProfession.NITWIT) || k.equals(VillagerProfession.NONE))
                .orElse(false);
        if (isNitwit) {
            return true;
        }

        // Add-on takeover hook (e.g. Trade Picker: Mastery). If an add-on claims this villager it
        // fully replaces our picker flow for it — we stop here and let it drive the interaction.
        // With no add-on registered this loop is empty and behaviour is unchanged.
        for (VillagerInteractionHandler handler : TradePickerApi.interactionHandlers()) {
            if (handler.onInteract(player, villager) == VillagerInteractionHandler.Result.TAKEOVER) {
                return false; // add-on handled it; the listener returns SUCCESS
            }
        }

        // If the player's client doesn't have Trade Picker, we can't show the picker.
        // Intercepting the click would leave the villager doing nothing at all, so step
        // aside and let vanilla handle trading normally. Nudge the player once (per server
        // session) so they understand why picking isn't available.
        if (!Services.NETWORK.canSendOpenPicker(player)) {
            if (warnedNoModClients.add(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "Trade Picker is on the server but not your client — install it to choose "
                                + "villager trades. Falling back to normal trading."));
            }
            return true; // PASS to vanilla
        }

        VillagerProfileState state = VillagerProfileState.get(level);
        VillagerProfile profile = state.get(villager.getUUID());

        TradeOptimizer.LOGGER.debug("[interact] villager={} prof={} mlevel={} offers={} profile={} thread={}",
                villager.getUUID(), profName, merchantLevel,
                villager.getOffers() == null ? "null" : villager.getOffers().size(),
                profile == null ? "null" : ("picks=" + profile.picks().keySet() + " legacy=" + profile.legacy().keySet()),
                Thread.currentThread().getName());

        // Case 2: no profile yet. Two sub-cases.
        //   2a — TRULY FRESH villager: level 1 and zero XP means vanilla rolled the
        //        starter trades but the player has never used them. Treat the random
        //        rolls as throwaway and open the picker so the player chooses their
        //        first two trades. Anything else and we'd be hiding the picker behind
        //        a Reset click for the most common "I just gave them a workstation"
        //        case.
        //   2b — PRE-EXISTING villager: level 2+, or level 1 with XP > 0 (they've
        //        already traded a bit). Import what's there as legacy so their progress
        //        survives the mod being installed, then open the merchant.
        if (profile == null) {
            // First interaction claims ownership. Only the owner (or an op) may later
            // reset this villager — stops a passerby from wiping a base's villagers.
            profile = VillagerProfile.fresh(villager.getUUID(), profName, player.getUUID());
            MerchantOffers existing = villager.getOffers();
            boolean trulyFresh = merchantLevel == 1 && villager.getVillagerXp() == 0;
            if (existing != null && !existing.isEmpty() && !trulyFresh) {
                importExistingOffers(level, villager, profile, existing, merchantLevel);
                state.update(profile);
                TradeOptimizer.LOGGER.info("Imported {} existing offers from villager {} into legacy (owner={})",
                        existing.size(), villager.getUUID(), player.getName().getString());
                // They already have offers — open the merchant ourselves to keep
                // every "filled villager" path consistent. Letting vanilla handle it
                // here was the source of the 1-frame / no-open bug since vanilla's
                // mobInteract silently no-ops in our flow.
                applyToVillager(level, villager, profile, player);
                openMerchant(villager, player, merchantLevel);
                return false;
            }
            if (trulyFresh && existing != null && !existing.isEmpty()) {
                // Wipe the vanilla-rolled offers before the picker opens, otherwise the
                // player would see them flash for a tick. They'll be replaced by the
                // player's picks once they hit Confirm.
                Services.PLATFORM.setVillagerOffers(villager, new MerchantOffers());
                TradeOptimizer.LOGGER.info("Truly fresh villager {} — discarding vanilla starter rolls, opening picker (owner={})",
                        villager.getUUID(), player.getName().getString());
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

        // Has entries (picks or legacy) for the current level — open the merchant menu directly
        // via openMerchant (which handles the stale-session teardown, see that method).
        //
        // We do NOT rebuild the offers here. Regenerating them on every open reset each
        // trade's use-count, so picked trades restocked instantly and bypassed vanilla's
        // cooldown. The offers were already built when the picks were chosen and vanilla
        // persists them with the villager, so reuse what's live and only refresh
        // reputation / Hero-of-the-Village discounts. Rebuild only if they're somehow
        // missing (e.g. another mechanic wiped them).
        MerchantOffers live = villager.getOffers();
        if (live == null || live.isEmpty()) {
            applyToVillager(level, villager, profile, player);
        } else {
            refreshSpecialPrices(villager, player);
        }
        openMerchant(villager, player, merchantLevel);
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
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getResourceKey(prof)
                .map(k -> k.location().toString()).orElse("minecraft:none");

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
        List<AvailableTrade> available;
        try {
            available = OfferFactory.enumerate(sl, villager, level);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("[submit] enumeration failed for {} level {}", profName, level, e);
            return;
        }
        Set<ResourceLocation> validIds = new HashSet<>();
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

        // How many picks this level legitimately allows = vanilla's per-level trade count (capped 2),
        // matching what sendPicker told the client. A tampered client could submit more cards (e.g.
        // five enchanted-gear variants from a single listing) to stuff the villager — trim to the cap.
        int picksRequired = Math.max(1, perLevelTradeCount(sl, villager.getVillagerData().profession().value(), level));
        if (validatedPicks.size() > picksRequired) {
            TradeOptimizer.LOGGER.warn("[submit] {} picks exceed allowed {} for {} level {} — trimming",
                    validatedPicks.size(), picksRequired, profName, level);
            validatedPicks = new ArrayList<>(validatedPicks.subList(0, picksRequired));
        }

        // 3) Enforce the per-level book cap (vanillaBookLimits). The client greys out excess book
        //    selections, but a tampered client could submit more — reject here. Uses the same
        //    bookPickCap as sendPicker (incl. the no-softlock relaxation), so a legitimate
        //    submission is never rejected. No-op when the toggle is off (cap == picksRequired).
        int bookCap = bookPickCap(sl, villager, level, available, picksRequired);
        int bookPicks = 0;
        for (TradeKey k : validatedPicks) {
            if (OfferFactory.isBookKey(k)) bookPicks++;
        }
        if (bookPicks > bookCap) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: {} book picks exceed cap {} for {} level {}",
                    bookPicks, bookCap, profName, level);
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: too many enchanted-book trades for this villager level."));
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
        // again after confirming picks. Same open path (with stale-session teardown)
        // we use in onInteract.
        openMerchant(villager, player, level);
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
        // 1.21.5: VillagerData.setLevel() → withLevel()
        villager.setVillagerData(current.withLevel(1));
        villager.setVillagerXp(0);
        Services.PLATFORM.setVillagerOffers(villager, new MerchantOffers());

        // Reset is driven from the merchant screen (Reset button -> confirm dialog), but the client
        // only swaps screens; it never asks the server to close the trade container. So the
        // server-side MerchantMenu and the villager's tradingPlayer both linger past the reset.
        // The next openTradingScreen would then close that stale menu *during* its own openMenu, and
        // the stale menu's removed() nulls tradingPlayer right after we set it -> the freshly opened
        // menu fails MerchantMenu.stillValid() on the next tick and self-closes after one frame
        // (the "first reopen after reset flashes, second is fine" bug). Tear the stale session down
        // now so the next open starts from a clean slate.
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        villager.setTradingPlayer(null);

        player.sendSystemMessage(Component.literal(
                "Villager reset to Novice. Right-click to pick new trades."));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Open the merchant menu for {@code player}, robust against a stale trade session.
     *
     * Closes whatever container the player still has open FIRST, then sets the trading player, then
     * opens the merchant. The order matters because of how vanilla wires these together:
     *
     *   - {@code openTradingScreen} -> {@code ServerPlayer.openMenu} ALSO closes the current
     *     container, but it does so AFTER we would have set the trading player.
     *   - When that container is a leftover {@code MerchantMenu} for THIS villager, its
     *     {@code removed()} calls {@code villager.setTradingPlayer(null)} — nulling the trading
     *     player we just set, out from under the brand-new menu.
     *   - {@code MerchantMenu.stillValid()} is {@code (merchant.getTradingPlayer() == player)}, so
     *     on the next tick the new menu fails validation and the server closes it: the trade screen
     *     flashes open then vanishes after a single frame ("can pick but can't trade").
     *
     * Tearing the stale session down up front means {@code openMenu} has nothing left to close, so
     * the trading player we set below survives. Same teardown the reset path uses; doing it here
     * makes every merchant-open site idempotent regardless of any lingering session.
     */
    private static void openMerchant(Villager villager, ServerPlayer player, int merchantLevel) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), merchantLevel);
    }

    /**
     * Split a flat MerchantOffers list into per-level legacy buckets.
     *
     * Vanilla appends each level's trades in order (level 1 first, then level 2, ...), but
     * the count per level is NOT always 2: a level whose trade pool is smaller produces
     * fewer (e.g. a toolsmith's master level offers only the diamond pickaxe), and data
     * packs / mods can change counts too. The old code assumed a flat 2-per-level grid, so
     * any villager that didn't match it got trades filed at the wrong level — and offers
     * past the assumed grid were dropped entirely.
     *
     * Instead we ask each level's trade set how many trades it can yield (capped at
     * vanilla's per-level maximum) to walk the flat list, and let the villager's current
     * (highest) level act as a catch-all so no offer is ever lost.
     *
     * Package-private (not private) so the legacy-bucketing gametest can call it directly:
     * the only public caller, onInteract, is gated behind ServerPlayNetworking.canSend, which
     * a headless mock player can't satisfy. No behaviour change — visibility only.
     */
    static void importExistingOffers(ServerLevel level, Villager villager,
                                             VillagerProfile profile, MerchantOffers existing, int merchantLevel) {
        VillagerProfession prof = villager.getVillagerData().profession().value();

        int total = existing.size();
        int idx = 0;
        for (int lvl = 1; lvl <= merchantLevel && idx < total; lvl++) {
            int count;
            if (lvl == merchantLevel) {
                // Last (current) level takes everything still unassigned, so a villager
                // with more offers than the expected grid never loses any.
                count = total - idx;
            } else {
                count = Math.min(perLevelTradeCount(level, prof, lvl), total - idx);
            }
            if (count <= 0) continue;
            List<MerchantOffer> bucket = new ArrayList<>(existing.subList(idx, idx + count));
            profile.setLegacy(lvl, bucket);
            idx += count;
        }
    }

    /** Vanilla generates at most this many trades per merchant level. */
    private static final int MAX_TRADES_PER_LEVEL = 2;

    /** How many offers a level is expected to contribute: its trade-set size, capped. */
    private static int perLevelTradeCount(ServerLevel level, VillagerProfession prof, int merchantLevel) {
        VillagerTrades.ItemListing[] listings = OfferFactory.listingsFor(level, prof, merchantLevel);
        if (listings == null) return MAX_TRADES_PER_LEVEL;
        return Math.min(MAX_TRADES_PER_LEVEL, listings.length);
    }

    /**
     * How many of the {@code picksRequired} selections may be enchanted books at this level.
     *
     * With {@code vanillaBookLimits} OFF this is just {@code picksRequired} — no effective limit, so
     * the picker behaves exactly as before. With it ON it's vanilla's per-level book-trade count
     * ({@link OfferFactory#countBookTemplates}, usually 1), forcing the remaining picks onto non-book
     * trades. The cap is never lowered so far that the level can't be filled: if there aren't enough
     * non-book options to cover the rest, it rises so books can fill the gap (prevents a softlock on
     * an all-books pool). The server and the client compute this identically, so a legitimate
     * submission is never rejected.
     */
    private static int bookPickCap(ServerLevel level, Villager villager, int merchantLevel,
                                   List<AvailableTrade> available, int picksRequired) {
        if (!TradeOptimizerConfig.get().vanillaBookLimits()) return picksRequired;
        int nonBookCards = 0;
        for (AvailableTrade t : available) {
            if (!OfferFactory.isBookKey(t.key())) nonBookCards++;
        }
        // No book trades in this pool (e.g. a non-librarian like a stonemason) — there's nothing to
        // cap, so report the normal pick count. This also stops the picker from showing a redundant
        // "(max 0 book)" hint for villagers that sell no books at all.
        if (nonBookCards == available.size()) return picksRequired;
        int cap = Math.min(OfferFactory.countBookTemplates(level, villager, merchantLevel), picksRequired);
        cap = Math.max(cap, picksRequired - nonBookCards); // never make the level impossible to fill
        return Math.min(cap, picksRequired);
    }

    private static void sendPicker(ServerPlayer player, Villager villager, VillagerProfile profile, int merchantLevel) {
        ServerLevel level = player.level();

        List<AvailableTrade> available;
        try {
            available = OfferFactory.enumerate(level, villager, merchantLevel);
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

        // How many trades the player must choose = how many vanilla actually grants at this level
        // (the pool's listing count, capped at 2). Used to be hardcoded 2 — fine while a single
        // listing showed one card, but enchanted gear / tipped arrows now expand one listing into
        // many cards, so demanding 2 picks there would hand out two trades where vanilla gives one.
        int picksRequired = Math.max(1, perLevelTradeCount(level, villager.getVillagerData().profession().value(), merchantLevel));

        // No-choice fast path: when the expanded pool has no more cards than picks required there's
        // nothing to choose, so skip the picker and apply every card. A single listing that DID
        // expand (many enchant/potion cards) has more cards than picks, so it still shows the picker.
        if (available.size() <= picksRequired) {
            List<TradeKey> autoPicks = new ArrayList<>(available.size());
            for (AvailableTrade trade : available) autoPicks.add(trade.key());

            VillagerProfileState state = VillagerProfileState.get(level);
            profile.setPicks(merchantLevel, autoPicks);
            state.update(profile);

            applyToVillager(level, villager, profile, player);
            openMerchant(villager, player, merchantLevel);

            TradeOptimizer.LOGGER.info("Auto-progressed {} level {}: {} option(s), no choice needed",
                    profile.profession(), merchantLevel, available.size());
            return;
        }

        TradeOptimizer.LOGGER.info("Picker for {} level {}: {} trade options, pick {}",
                profile.profession(), merchantLevel, available.size(), picksRequired);

        int maxBookPicks = bookPickCap(level, villager, merchantLevel, available, picksRequired);

        OpenPickerS2C payload = new OpenPickerS2C(
                villager.getUUID(),
                profile.profession(),
                merchantLevel,
                picksRequired,
                maxBookPicks,
                available
        );
        if (!Services.NETWORK.canSendOpenPicker(player)) {
            TradeOptimizer.LOGGER.warn("Client can't receive OPEN_PICKER (mod missing on client?)");
            return;
        }
        try {
            Services.NETWORK.sendOpenPicker(player, payload);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("Failed to send picker payload ({} trades)",
                    available.size(), e);
            player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: picker send failed (see server log)."));
        }
    }

    /**
     * Rebuild the villager's offers from profile state, preserving use-counts on trades that
     * carry over from the previous offer list.
     *
     * Picks are regenerated from their TradeKeys, but a freshly generated MerchantOffer starts
     * with a zero use-count. If we simply replaced the live offers with fresh ones, every
     * previously-used trade would be silently restocked — so leveling a villager and picking the
     * new level's trades would hand out a free restock on all the lower-level trades (audit #3).
     * To prevent that, when a regenerated pick matches a trade already present in the live offers
     * (same result + base cost), we KEEP the existing offer instance so its accumulated uses (and
     * demand) survive. Only a genuinely new pick gets a fresh, empty offer.
     *
     * Legacy levels keep their imported MerchantOffers verbatim — the profile holds those exact
     * instances and re-adds them below, so their use-counts are already preserved across rebuilds.
     * They're excluded from the carry-over pool so a pick can't consume (and then duplicate) one.
     *
     * Reputation modifiers (from curing or hero of the village) are applied if a player is provided.
     */
    private static void applyToVillager(ServerLevel level, Villager villager, VillagerProfile profile, ServerPlayer player) {
        int currentLevel = villager.getVillagerData().level();

        // Carry-over pool of the previously-live PICK offers, used to preserve use-counts. Legacy
        // offers are filtered out by identity: the profile re-adds those same instances, so reusing
        // one here would list it twice.
        Set<MerchantOffer> legacyInstances = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<MerchantOffer> bucket : profile.legacy().values()) legacyInstances.addAll(bucket);
        List<MerchantOffer> carryOver = new ArrayList<>();
        MerchantOffers previous = villager.getOffers();
        if (previous != null) {
            for (MerchantOffer offer : previous) {
                if (!legacyInstances.contains(offer)) carryOver.add(offer);
            }
        }

        MerchantOffers offers = new MerchantOffers();
        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            for (TradeKey key : profile.picksFor(lvl)) {
                Optional<MerchantOffer> generated = OfferFactory.generate(level, villager, key, lvl);
                if (generated.isEmpty()) {
                    TradeOptimizer.LOGGER.warn("[apply] generate({}) lvl={} returned EMPTY",
                            key.id(), lvl);
                    continue;
                }
                MerchantOffer fresh = generated.get();
                MerchantOffer kept = takeMatching(carryOver, fresh);
                offers.add(kept != null ? kept : fresh);
            }
            offers.addAll(profile.legacyFor(lvl));
        }

        Services.PLATFORM.setVillagerOffers(villager, offers);
        refreshSpecialPrices(villager, player);
    }

    /**
     * Find and remove from {@code pool} an offer representing the same trade as {@code target} —
     * same result and same BASE cost (base, so a reputation / Hero discount already written into
     * one offer's price doesn't read as a difference). Returns the carried-over instance, or null
     * if the pool has no match. Removing the match stops two identical picks from both claiming it.
     */
    private static MerchantOffer takeMatching(List<MerchantOffer> pool, MerchantOffer target) {
        for (int i = 0; i < pool.size(); i++) {
            MerchantOffer candidate = pool.get(i);
            if (ItemStack.matches(candidate.getResult(), target.getResult())
                    && ItemStack.matches(candidate.getBaseCostA(), target.getBaseCostA())
                    && ItemStack.matches(candidate.getCostB(), target.getCostB())) {
                return pool.remove(i);
            }
        }
        return null;
    }

    /**
     * Re-apply reputation and Hero-of-the-Village discounts to the villager's current
     * offers, mirroring vanilla's startTrading order.
     *
     * Vanilla computes these in Villager.updateSpecialPrices(player), writing each
     * discount into the offer's specialPriceDiff, and only calls it from startTrading().
     * Because we open the merchant manually (setTradingPlayer + openTradingScreen, to
     * dodge the 1-frame menu bug) that call was being skipped, so curing / Hero discounts
     * never applied. We reproduce it here.
     *
     * updateSpecialPrices ACCUMULATES (it adds to specialPriceDiff, never resets), so we
     * clear each offer first — otherwise re-opening a villager would stack the discount
     * every time.
     */
    private static void refreshSpecialPrices(Villager villager, ServerPlayer player) {
        if (player == null) return;
        MerchantOffers offers = villager.getOffers();
        if (offers == null) return;
        for (MerchantOffer offer : offers) {
            offer.resetSpecialPriceDiff();
        }
        Services.PLATFORM.updateVillagerSpecialPrices(villager, player);
    }
}
