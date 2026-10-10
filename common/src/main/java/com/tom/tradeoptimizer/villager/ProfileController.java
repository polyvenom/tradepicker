package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.mixin.VillagerInvoker;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.platform.Services;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;

import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** Op-equivalent probe: GAMEMASTERS is the named permission level 2 (/op'd command access). */
    private static final Permission OP_PERMISSION =
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(OP_PERMISSION);
    }

    /** Reach leeway past the player's entity interaction range — what vanilla allows an open container. */
    private static final double REACH_LEEWAY = 4.0;

    /**
     * The trust gate for every player action on a villager (right-click and both packet handlers, #9).
     * A modified client can send a submit or reset for any villager UUID from anywhere, so re-check
     * what vanilla interaction guarantees: alive, same level, within reach, not trading with someone else.
     */
    static boolean canAct(ServerPlayer player, Villager villager) {
        if (!villager.isAlive() || villager.level() != player.level()) return false;
        if (!player.isWithinEntityInteractionRange(villager, REACH_LEEWAY)) return false;
        var trader = villager.getTradingPlayer();
        return trader == null || trader == player;
    }

    /**
     * Clicks handed straight to vanilla (#10): the two item interactions vanilla settles before
     * trading, villagers that can't trade (nitwit, unemployed), and clicks vanilla refuses (baby,
     * sleeping, dying or busy). Nothing else passes through: vanilla's own trade window would roll
     * random trades that compete with the picker's.
     */
    static boolean leaveToVanilla(ServerPlayer player, Villager villager) {
        if (isVanillaItemInteraction(player.getMainHandItem(), villager)) return true;
        Holder<VillagerProfession> prof = villager.getVillagerData().profession();
        if (prof.is(VillagerProfession.NITWIT) || prof.is(VillagerProfession.NONE)) return true;
        return villager.isBaby() || villager.isSleeping() || !canAct(player, villager);
    }

    /**
     * May this player write picks for this villager? Its owner, or an op. An unowned villager
     * (no profile yet, or a grandfathered one) belongs to whoever claims it first.
     */
    private static boolean mayEdit(ServerPlayer player, VillagerProfile profile) {
        return profile == null || profile.owner().isEmpty()
                || profile.owner().get().equals(player.getUUID()) || isOp(player);
    }

    /** Players without the client mod who were already told once, so the notice doesn't repeat per click. */
    private static final Set<UUID> warnedNoModClients = ConcurrentHashMap.newKeySet();

    /**
     * Called on every right-click of a villager: opens the merchant directly or sends the picker.
     *
     * @return true when there was nothing for us to do (vanilla handles it, so the listener passes);
     *         false when we handled the interaction.
     */
    public static boolean onInteract(ServerPlayer player, Villager villager) {
        ServerLevel level = player.level();
        VillagerData data = villager.getVillagerData();
        int merchantLevel = data.level();
        Holder<VillagerProfession> profHolder = data.profession();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profHolder.value()).toString();

        if (leaveToVanilla(player, villager)) {
            return true; // PASS to vanilla
        }

        // Without the client mod we can't show the picker and intercepting would do nothing,
        // so let vanilla trade normally.
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

        if (TradeOptimizer.LOGGER.isDebugEnabled()) { // the arguments build strings on every click (#20)
            TradeOptimizer.LOGGER.debug("[interact] villager={} prof={} mlevel={} offers={} profile={} thread={}",
                    villager.getUUID(), profName, merchantLevel,
                    villager.getOffers() == null ? "null" : villager.getOffers().size(),
                    profile == null ? "null" : ("picks=" + profile.picks().keySet() + " legacy=" + profile.legacy().keySet()),
                    Thread.currentThread().getName());
        }

        // No profile yet. Truly fresh (level 1, 0 XP): vanilla's starter rolls were never used, so
        // discard them and open the picker. Otherwise it's pre-existing: import its offers as legacy
        // so progress survives installing the mod, then open the merchant.
        if (profile == null) {
            // First interaction claims ownership; only the owner (or an op) may later reset.
            profile = VillagerProfile.fresh(villager.getUUID(), profName, player.getUUID());
            MerchantOffers existing = villager.getOffers();
            boolean trulyFresh = merchantLevel == 1 && villager.getVillagerXp() == 0;
            if (existing != null && !existing.isEmpty() && !trulyFresh) {
                importExistingOffers(level, villager, profile, existing, merchantLevel);
                state.update(profile);
                TradeOptimizer.LOGGER.info("Imported {} existing offers from villager {} into legacy (owner={})",
                        existing.size(), villager.getUUID(), player.getName().getString());
                // Open the merchant ourselves: vanilla's mobInteract silently no-ops in our flow.
                applyToVillager(level, villager, profile);
                openMerchant(villager, player, merchantLevel);
                return false;
            }
            if (trulyFresh && existing != null && !existing.isEmpty()) {
                // Wipe the starter rolls before the picker opens so they don't flash for a tick.
                villager.setOffers(new MerchantOffers());
                TradeOptimizer.LOGGER.info("Truly fresh villager {} — discarding vanilla starter rolls, opening picker (owner={})",
                        villager.getUUID(), player.getName().getString());
            }
            state.update(profile);
        } else if (!profile.profession().equals(profName)) {
            // Profession changed (workstation switched): start over, keeping the owner.
            UUID keepOwner = profile.owner().orElse(player.getUUID());
            profile = VillagerProfile.fresh(villager.getUUID(), profName, keepOwner);
            state.update(profile);
        } else if (profile.owner().isEmpty()) {
            // Profile saved before ownership existed: the first right-click claims it.
            profile = profile.withOwner(player.getUUID());
            state.update(profile);
            TradeOptimizer.LOGGER.info("Claimed ownership of grandfathered villager {} for {}",
                    villager.getUUID(), player.getName().getString());
        }

        // A rank can be earned and picked but never granted; grant it before anything reads the rank.
        grantRanksAlreadyPicked(level, villager, profile);
        merchantLevel = villager.getVillagerData().level();

        // Only the owner (or an op) chooses trades, including sendPicker's no-choice auto-fill.
        // Anyone else trades with what the owner has set up.
        if (!mayEdit(player, profile)) {
            MerchantOffers live = villager.getOffers();
            if (live == null || live.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "Trade Picker: this villager's owner hasn't picked its trades yet."));
                return false;
            }
            openMerchant(villager, player, merchantLevel);
            return false;
        }

        // Pick for the LOWEST unpicked level: vanilla banks level-ups while trading, so a villager
        // can arrive here two levels ahead of its picks.
        int unfilled = firstUnfilledLevel(profile, merchantLevel);
        if (unfilled > 0 && sendPicker(player, villager, profile, unfilled)) {
            return false;
        }

        // All levels up to the current one are picked. If the next rank is earned, its level-up is
        // held (VillagerLevelUpMixin) until the player picks, so offer that picker now.
        if (isDueToLevelUp(villager)
                && !profile.isFilled(merchantLevel + 1)
                && sendPicker(player, villager, profile, merchantLevel + 1)) {
            return false;
        }

        // Don't rebuild live offers on every open: regenerating resets each trade's use-count,
        // which would restock picked trades instantly. Rebuild only if they're missing.
        MerchantOffers live = villager.getOffers();
        if (live == null || live.isEmpty()) {
            applyToVillager(level, villager, profile);
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
        if (!canAct(player, villager)) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: {} can't act on villager {} (dead, out of reach, or busy)",
                    player.getName().getString(), villagerId);
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: couldn't apply your picks (too far away, or the villager is busy). Right-click it again."));
            return;
        }
        VillagerProfession prof = villager.getVillagerData().profession().value();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof).toString();

        // Ownership first: it's cheap, and nothing below should run for someone who may not edit.
        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);
        if (!mayEdit(player, profile)) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: villager {} owned by {}, not {}",
                    villagerId, profile.owner().get(), player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: this villager belongs to another player."));
            return;
        }
        // No profile means the player never right-clicked this villager, so the picker was never
        // opened for it. Only a truly fresh villager may be claimed straight from a submit: any
        // other one has trades that onInteract would import as legacy, and a crafted packet would
        // overwrite them.
        if (profile == null) {
            MerchantOffers existing = villager.getOffers();
            boolean trulyFresh = villager.getVillagerData().level() == 1 && villager.getVillagerXp() == 0;
            if (existing != null && !existing.isEmpty() && !trulyFresh) {
                TradeOptimizer.LOGGER.warn("[submit] rejected: villager {} was never opened in the picker", villagerId);
                return;
            }
        }
        // A level is picked once. Re-picking goes through Reset, which starts the villager over.
        if (profile != null && profile.isFilled(level)) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: level {} of villager {} is already picked", level, villagerId);
            return;
        }

        // The picks arrive from the client and can't be trusted: a modified client could submit any
        // trade id or synthetic book key and get it built at minimum cost.

        // 1) The level must be one the villager has reached, or the one above it when its
        //    level-up is being held for this picker. Anything else is a tampered packet.
        int currentLevel = villager.getVillagerData().level();
        boolean levellingUp = level == currentLevel + 1 && isDueToLevelUp(villager);
        if (level < 1 || (level > currentLevel && !levellingUp)) {
            TradeOptimizer.LOGGER.warn("[submit] rejected out-of-range level {} for villager {} (at level {})",
                    level, villagerId, currentLevel);
            return;
        }

        // 2) Every pick must be a real option in this (profession, level) pool.
        ResourceKey<TradeSet> tradeSetKey = prof.getTrades(level);
        if (tradeSetKey == null) {
            TradeOptimizer.LOGGER.warn("[submit] no trade set for {} level {}", profName, level);
            return;
        }
        List<AvailableTrade> available;
        try {
            available = enumeratePool(sl, villager, tradeSetKey);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("[submit] enumeration failed for {} level {}", profName, level, e);
            return;
        }
        Set<Identifier> validIds = new HashSet<>();
        for (AvailableTrade t : available) validIds.add(t.key().id());

        List<TradeKey> validatedPicks = new ArrayList<>(picks.size());
        Set<Identifier> seen = new HashSet<>();
        for (TradeKey k : picks) {
            if (!validIds.contains(k.id())) {
                TradeOptimizer.LOGGER.warn("[submit] dropped invalid pick {} for {} level {}", k.id(), profName, level);
            } else if (!seen.add(k.id())) {
                // The picker selects distinct cards, so a repeated key is a crafted packet.
                TradeOptimizer.LOGGER.warn("[submit] dropped duplicate pick {} for {} level {}", k.id(), profName, level);
            } else {
                validatedPicks.add(k);
            }
        }
        if (validatedPicks.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: those trades aren't valid for this villager."));
            return;
        }

        // Picks allowed = vanilla's per-level trade count, as sendPicker told the client. Trim extras
        // a tampered client might send (e.g. five gear variants of a single template).
        int picksRequired = Math.max(1, perLevelTradeCount(sl.registryAccess(), prof, level));
        if (validatedPicks.size() > picksRequired) {
            TradeOptimizer.LOGGER.warn("[submit] {} picks exceed allowed {} for {} level {} — trimming",
                    validatedPicks.size(), picksRequired, profName, level);
            validatedPicks = new ArrayList<>(validatedPicks.subList(0, picksRequired));
        }
        // And no fewer: a level is locked once picked, so an under-filled submit would stay short.
        if (validatedPicks.size() < Math.min(picksRequired, available.size())) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: {} picks, {} required for {} level {}",
                    validatedPicks.size(), picksRequired, profName, level);
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: those trades aren't valid for this villager."));
            return;
        }

        // 3) Enforce the per-level book cap (vanillaBookLimits) with the same bookPickCap sendPicker
        //    used, over the same hidePickedTrades-filtered card list the client saw; a different list
        //    could yield a different cap and reject a legitimate submit.
        List<AvailableTrade> shownToClient = profile == null ? available
                : effectiveAvailable(available, ownedTradeKeys(profile, available), picksRequired);
        int bookCap = bookPickCap(sl, villager, tradeSetKey, shownToClient, picksRequired);
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

        if (profile == null) {
            // A truly fresh villager (checked above): claim ownership now.
            profile = VillagerProfile.fresh(villagerId, profName, player.getUUID());
        } else if (profile.owner().isEmpty()) {
            profile = profile.withOwner(player.getUUID());
        }

        profile.setPicks(level, validatedPicks);
        state.update(profile);

        // Grant the held level-up now (vanilla's increaseMerchantCareer minus its random trade roll).
        if (levellingUp && grantPendingLevel(villager, level)) {
            currentLevel = level;
            TradeOptimizer.LOGGER.info("Levelled {} to {} on the player's picks",
                    profName, level);
        }

        applyToVillager(sl, villager, profile);

        // Several levels can be banked (vanilla drains its queue only when trading stops): chain into
        // the next picker, or the skipped levels' trades are lost for good.
        int nextUnfilled = firstUnfilledLevel(profile, currentLevel);
        if (nextUnfilled > 0 && sendPicker(player, villager, profile, nextUnfilled)) {
            return;
        }

        // Open the merchant so the player needn't right-click again after confirming.
        openMerchant(villager, player, level);
    }

    /**
     * Called after any mob conversion (both loaders wire their conversion event here).
     *
     * Vanilla's Mob.convertTo discards the old entity and never copies its UUID, and profiles are
     * keyed by villager UUID. Re-keying on every conversion keeps the profile attached through
     * villager -> zombie villager -> cured villager; otherwise the cured villager keeps its offers
     * (vanilla copies those) but loses picks and owner, and they get re-imported as legacy.
     */
    public static void onMobConverted(net.minecraft.world.entity.LivingEntity previous,
                                      net.minecraft.world.entity.LivingEntity converted) {
        if (!(previous.level() instanceof ServerLevel level)) return;
        VillagerProfileState state = VillagerProfileState.get(level);
        if (state.rekey(previous.getUUID(), converted.getUUID())) {
            TradeOptimizer.LOGGER.info("Re-keyed villager profile {} -> {} ({} conversion)",
                    previous.getUUID(), converted.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(converted.getType()));
        }
    }

    public static void onReset(ServerPlayer player, UUID villagerId) {
        ServerLevel sl = player.level();
        if (!(sl.getEntity(villagerId) instanceof Villager villager)) return;
        if (!canAct(player, villager)) {
            TradeOptimizer.LOGGER.warn("[reset] rejected: {} can't act on villager {} (dead, out of reach, or busy)",
                    player.getName().getString(), villagerId);
            return;
        }

        // Reset is destructive (wipes XP, level and locked-in trades): owner or op only.
        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);

        boolean op = isOp(player);

        if (profile == null) {
            // Unclaimed: reject so a crafted packet can't wipe a stranger's vanilla villager.
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
            // The owner keeps ownership across a reset.
            UUID keepOwner = profile.owner().orElse(player.getUUID());
            String profName = profile.profession();
            profile = VillagerProfile.fresh(villagerId, profName, keepOwner);
            state.update(profile);
        }

        VillagerData current = villager.getVillagerData();
        villager.setVillagerData(current.withLevel(1));
        villager.setVillagerXp(0);
        villager.setOffers(new MerchantOffers());

        // The client only swaps screens on reset; the server-side MerchantMenu and tradingPlayer
        // linger. The next open would close that stale menu mid-openMenu, its removed() would null
        // tradingPlayer, and the new menu would fail stillValid() and close after one frame.
        // Only this villager's session: a reset packet must not close whatever else the sender has
        // open (#9).
        if (villager.getTradingPlayer() == player) {
            if (player.containerMenu instanceof MerchantMenu) player.closeContainer();
            villager.setTradingPlayer(null);
        }

        player.sendSystemMessage(Component.literal(
                "Villager reset to Novice. Right-click to pick new trades."));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Open the merchant menu: close any stale trade session, THEN refresh the per-player
     * discounts, THEN open. The order is load-bearing, and this must be idempotent.
     *
     * The vanilla client sends two packets per entity right-click (interact_at, interact) and the
     * loader's interact event fires for each, so onInteract runs twice per click; on the second run
     * the open container is the MerchantMenu the first run created. openMenu closes the current
     * container first, and MerchantMenu.removed() calls setTradingPlayer(null), which runs
     * resetSpecialPrices() (zeroing every discount) and nulls the tradingPlayer that
     * MerchantMenu.stillValid() needs (the new menu would close after one frame).
     *
     * Closing the stale container up front leaves openMenu nothing to tear down, and refreshing
     * discounts after setTradingPlayer means the offers sent to the client are the discounted ones.
     */
    static void openMerchant(Villager villager, ServerPlayer player, int merchantLevel) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        villager.setTradingPlayer(player);
        refreshSpecialPrices(villager, player);
        villager.openTradingScreen(player, villager.getDisplayName(), merchantLevel);
    }

    /**
     * Split a flat MerchantOffers list into per-level legacy buckets.
     *
     * Vanilla appends each level's trades in order, but the count per level isn't always 2 (a
     * toolsmith's master level offers only the diamond pickaxe; data packs change counts). So walk
     * the list using each level's trade-set size (capped), with the current level as a catch-all
     * so no offer is lost.
     *
     * Package-private for the legacy-bucketing gametest (onInteract needs a modded client).
     */
    static void importExistingOffers(ServerLevel level, Villager villager,
                                             VillagerProfile profile, MerchantOffers existing, int merchantLevel) {
        HolderLookup.Provider registries = level.registryAccess();
        VillagerProfession prof = villager.getVillagerData().profession().value();

        int total = existing.size();
        int idx = 0;
        for (int lvl = 1; lvl <= merchantLevel && idx < total; lvl++) {
            int count;
            if (lvl == merchantLevel) {
                // The current level takes everything still unassigned.
                count = total - idx;
            } else {
                count = Math.min(perLevelTradeCount(registries, prof, lvl), total - idx);
            }
            if (count <= 0) continue;
            List<MerchantOffer> bucket = new ArrayList<>(existing.subList(idx, idx + count));
            profile.setLegacy(lvl, bucket);
            idx += count;
        }
    }

    /**
     * Mirrors {@code Mob.checkAndHandleImportantInteractions}: the only two item interactions
     * vanilla settles before trading. Deliberately narrow: an unnamed name tag or a spawn egg for
     * another mob falls through to vanilla's trade screen, so passing those along would open the
     * vanilla merchant UI instead of the picker.
     */
    static boolean isVanillaItemInteraction(ItemStack stack, Villager villager) {
        if (stack.is(Items.NAME_TAG)) {
            return stack.has(DataComponents.CUSTOM_NAME);
        }
        return SpawnEggItem.spawnsEntity(stack, villager.getType());
    }

    /**
     * The last picker each player was sent. onInteract runs twice per click (see openMerchant), and
     * a second payload would make the client build the screen twice and blink.
     */
    private record PickerSend(UUID villagerId, int merchantLevel, long gameTime) {}

    private static final Map<UUID, PickerSend> lastPickerSend = new HashMap<>();

    /** Two runs of the same click land in the same tick, or within one of it under load. */
    private static final long DUPLICATE_CLICK_TICKS = 2;

    /**
     * The last trade pool enumerated for each villager, reused by the click's second run and the
     * submit's validation (#20). Enumerating is expensive (headline gear re-rolls vanilla's
     * enchanting up to 256 times per enchantment) and depends only on the villager, trade set and
     * config; validating against the list the picker was built from is also stricter.
     */
    private record PoolEntry(ResourceKey<TradeSet> tradeSet, HolderLookup.Provider registries,
                             int configRevision, long gameTime, List<AvailableTrade> pool) {}

    private static final Map<UUID, PoolEntry> poolCache = new HashMap<>();

    /** Ten minutes: long enough for a player to read the picker, short enough to bound the map. */
    private static final long POOL_CACHE_TICKS = 12_000;

    /** {@link OfferFactory#enumerate}, cached per villager. The list is unmodifiable: it's shared. */
    static List<AvailableTrade> enumeratePool(ServerLevel level, Villager villager, ResourceKey<TradeSet> tradeSetKey) {
        long now = level.getGameTime();
        HolderLookup.Provider registries = level.registryAccess();
        int revision = TradeOptimizerConfig.revision();
        PoolEntry hit = poolCache.get(villager.getUUID());
        if (hit != null && hit.tradeSet().equals(tradeSetKey) && hit.registries() == registries
                && hit.configRevision() == revision && now >= hit.gameTime()
                && now - hit.gameTime() <= POOL_CACHE_TICKS) {
            return hit.pool();
        }
        List<AvailableTrade> pool = List.copyOf(OfferFactory.enumerate(level, villager, tradeSetKey));
        // Drop expired entries and any left over from another world.
        poolCache.values().removeIf(e -> e.registries() != registries
                || now < e.gameTime() || now - e.gameTime() > POOL_CACHE_TICKS);
        poolCache.put(villager.getUUID(), new PoolEntry(tradeSetKey, registries, revision, now, pool));
        return pool;
    }

    /** Vanilla generates at most this many trades per merchant level. */
    private static final int MAX_TRADES_PER_LEVEL = 2;

    /** How many offers a level is expected to contribute: its trade-set size, capped. */
    private static int perLevelTradeCount(HolderLookup.Provider registries, VillagerProfession prof, int level) {
        ResourceKey<TradeSet> key = prof.getTrades(level);
        if (key == null) return MAX_TRADES_PER_LEVEL;
        Optional<Holder.Reference<TradeSet>> setRef =
                registries.lookupOrThrow(Registries.TRADE_SET).get(key);
        if (setRef.isEmpty()) return MAX_TRADES_PER_LEVEL;
        int entries = setRef.get().value().trades().size();
        return Math.min(MAX_TRADES_PER_LEVEL, entries);
    }

    /**
     * How many of the {@code picksRequired} selections may be enchanted books at this level.
     *
     * With {@code vanillaBookLimits} off this is {@code picksRequired} (no limit). With it on it's
     * vanilla's per-level book-trade count ({@link OfferFactory#countBookTemplates}, usually 1),
     * raised when there aren't enough non-book options to fill the level. Server and client compute
     * this identically.
     */
    private static int bookPickCap(ServerLevel level, Villager villager, ResourceKey<TradeSet> tradeSetKey,
                                   List<AvailableTrade> available, int picksRequired) {
        if (!TradeOptimizerConfig.get().vanillaBookLimits()) return picksRequired;
        int nonBookCards = 0;
        for (AvailableTrade t : available) {
            if (!OfferFactory.isBookKey(t.key())) nonBookCards++;
        }
        // No book trades in this pool: nothing to cap (and no "(max 0 book)" hint in the picker).
        if (nonBookCards == available.size()) return picksRequired;
        int cap = Math.min(OfferFactory.countBookTemplates(level, villager, tradeSetKey), picksRequired);
        cap = Math.max(cap, picksRequired - nonBookCards); // never make the level impossible to fill
        return Math.min(cap, picksRequired);
    }

    /**
     * The keys within {@code available} this villager already sells: any key picked at any level,
     * plus any card whose preview result matches a legacy offer. Synthetic book/gear/arrow keys are
     * level-independent, so an earlier-level pick matches the identical card here; flat listing
     * keys are level-scoped and never collide (#7).
     */
    static List<TradeKey> ownedTradeKeys(VillagerProfile profile, List<AvailableTrade> available) {
        Set<Identifier> pickedIds = new HashSet<>();
        for (List<TradeKey> picks : profile.picks().values()) {
            for (TradeKey k : picks) pickedIds.add(k.id());
        }
        List<MerchantOffer> legacyOffers = new ArrayList<>();
        for (List<MerchantOffer> bucket : profile.legacy().values()) legacyOffers.addAll(bucket);

        List<TradeKey> owned = new ArrayList<>();
        for (AvailableTrade t : available) {
            boolean isOwned = pickedIds.contains(t.key().id());
            if (!isOwned) {
                for (MerchantOffer legacy : legacyOffers) {
                    if (ItemStack.matches(legacy.getResult(), t.previewOffer().getResult())) {
                        isOwned = true;
                        break;
                    }
                }
            }
            if (isOwned) owned.add(t.key());
        }
        return owned;
    }

    /**
     * The card list the picker shows. With hidePickedTrades off this is the full pool (owned cards
     * are only marked client-side). With it on, owned cards are removed unless that leaves fewer
     * cards than the level needs. Returns {@code available} itself when nothing was filtered.
     */
    static List<AvailableTrade> effectiveAvailable(List<AvailableTrade> available,
                                                   List<TradeKey> ownedKeys, int picksRequired) {
        if (!TradeOptimizerConfig.get().hidePickedTrades() || ownedKeys.isEmpty()) return available;
        Set<Identifier> hide = new HashSet<>();
        for (TradeKey k : ownedKeys) hide.add(k.id());
        List<AvailableTrade> out = new ArrayList<>();
        for (AvailableTrade t : available) {
            if (!hide.contains(t.key().id())) out.add(t);
        }
        return out.size() >= picksRequired ? out : available;
    }

    /**
     * Grant every held rank that is earned and already picked, then rebuild the offers.
     *
     * A rank can have stored picks but never have been granted. The picker treats it as done and
     * won't reopen, and applyToVillager only walks levels up to the current rank, so the villager
     * would stay a rank short for good. A rank that still needs choosing is left for the picker.
     */
    static void grantRanksAlreadyPicked(ServerLevel level, Villager villager, VillagerProfile profile) {
        int before = villager.getVillagerData().level();
        while (isDueToLevelUp(villager)
                && profile.isFilled(villager.getVillagerData().level() + 1)
                && grantPendingLevel(villager, villager.getVillagerData().level() + 1)) {
            // grantPendingLevel raised the rank; loop in case the XP covers another.
        }
        int after = villager.getVillagerData().level();
        if (after != before) {
            applyToVillager(level, villager, profile);
            TradeOptimizer.LOGGER.info("Granted {} from level {} to {} on picks already stored",
                    profile.profession(), before, after);
        }
    }

    /**
     * Grant a held level-up, when {@code level} is the rank the picker was opened for.
     *
     * Must run BEFORE the picks are applied: applyToVillager only walks levels 1..current, so a
     * rank granted afterwards would have its trades left out.
     */
    static boolean grantPendingLevel(Villager villager, int level) {
        if (level != villager.getVillagerData().level() + 1 || !isDueToLevelUp(villager)) return false;
        villager.setVillagerData(villager.getVillagerData().withLevel(level));
        return true;
    }

    /**
     * Vanilla's {@code shouldIncreaseLevel} condition (rank can rise, XP reached the bar),
     * recomputed because VillagerLevelUpMixin holds the level-up until the player has picked.
     */
    static boolean isDueToLevelUp(Villager villager) {
        int level = villager.getVillagerData().level();
        return VillagerData.canLevelUp(level)
                && villager.getVillagerXp() >= VillagerData.getMaxXpPerLevel(level);
    }

    /**
     * The lowest merchant level from 1..{@code currentLevel} that still has neither picks nor
     * legacy offers, or -1 when every level is accounted for.
     *
     * Scanning from the bottom keeps a multi-level jump from dropping a level's trades.
     */
    static int firstUnfilledLevel(VillagerProfile profile, int currentLevel) {
        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            if (!profile.isFilled(lvl)) return lvl;
        }
        return -1;
    }

    /**
     * @return true when this call took responsibility for what the player sees next — the picker
     *         payload was sent, or the level auto-progressed and the merchant was opened here.
     *         false means nothing was shown and the caller should fall back to opening the merchant.
     */
    static boolean sendPicker(ServerPlayer player, Villager villager, VillagerProfile profile, int merchantLevel) {
        ServerLevel level = player.level();
        VillagerProfession prof = villager.getVillagerData().profession().value();
        ResourceKey<TradeSet> tradeSetKey = prof.getTrades(merchantLevel);
        if (tradeSetKey == null) {
            TradeOptimizer.LOGGER.warn("No trade set for {} level {}", profile.profession(), merchantLevel);
            return false;
        }

        List<AvailableTrade> available;
        try {
            available = enumeratePool(level, villager, tradeSetKey);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("Trade enumeration failed for {} level {}",
                    profile.profession(), merchantLevel, e);
            player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: failed to enumerate trades (see server log)."));
            return false;
        }
        if (available.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "No trades available for " + profile.profession() + " level " + merchantLevel));
            return false;
        }

        // Picks required = trades vanilla grants at this level (trade-set size, capped at 2). A
        // single-template level grants 1 even when enchanted gear / tipped arrows expand it into many cards.
        int picksRequired = Math.max(1, perLevelTradeCount(level.registryAccess(), prof, merchantLevel));

        // No-choice fast path: no more cards than picks required, so skip the picker and apply them
        // all. A template that expanded into many enchant/potion cards still shows it (#4 / #5).
        if (available.size() <= picksRequired) {
            List<TradeKey> autoPicks = new ArrayList<>(available.size());
            for (AvailableTrade trade : available) autoPicks.add(trade.key());

            VillagerProfileState state = VillagerProfileState.get(level);

            // A rank with nothing to choose still has to be granted: this path bypasses
            // onPickerSubmit, and vanilla's own level-up is cancelled for managed villagers.
            boolean granted = grantPendingLevel(villager, merchantLevel);

            profile.setPicks(merchantLevel, autoPicks);
            state.update(profile);

            applyToVillager(level, villager, profile);

            TradeOptimizer.LOGGER.info("Auto-progressed {} level {}: {} option(s), no choice needed{}",
                    profile.profession(), merchantLevel, available.size(),
                    granted ? " (rank granted)" : "");

            // Another level may still be unpicked behind this one.
            int currentLevel = villager.getVillagerData().level();
            int next = firstUnfilledLevel(profile, currentLevel);
            if (next > 0 && next != merchantLevel) {
                return sendPicker(player, villager, profile, next);
            }
            openMerchant(villager, player, currentLevel);
            return true;
        }

        // Same click, villager and level: the payload is already on its way. Report it as handled.
        PickerSend send = new PickerSend(villager.getUUID(), merchantLevel, level.getGameTime());
        PickerSend previous = lastPickerSend.get(player.getUUID());
        if (previous != null
                && previous.villagerId().equals(send.villagerId())
                && previous.merchantLevel() == send.merchantLevel()
                && send.gameTime() - previous.gameTime() <= DUPLICATE_CLICK_TICKS) {
            return true;
        }
        lastPickerSend.put(player.getUUID(), send);

        TradeOptimizer.LOGGER.info("Picker for {} level {}: {} trade options, pick {}",
                profile.profession(), merchantLevel, available.size(), picksRequired);

        // Trades already on this villager are marked, or removed with hidePickedTrades (#7).
        List<TradeKey> ownedKeys = ownedTradeKeys(profile, available);
        List<AvailableTrade> shown = effectiveAvailable(available, ownedKeys, picksRequired);
        boolean hidOwned = shown != available;

        int maxBookPicks = bookPickCap(level, villager, tradeSetKey, shown, picksRequired);

        OpenPickerS2C payload = new OpenPickerS2C(
                villager.getUUID(),
                profile.profession(),
                merchantLevel,
                picksRequired,
                maxBookPicks,
                shown,
                hidOwned ? List.of() : ownedKeys
        );
        if (!Services.NETWORK.canSendOpenPicker(player)) {
            TradeOptimizer.LOGGER.warn("Client can't receive OPEN_PICKER (mod missing on client?)");
            return false;
        }
        try {
            Services.NETWORK.sendOpenPicker(player, payload);
            return true;
        } catch (Exception e) {
            TradeOptimizer.LOGGER.error("Failed to send picker payload ({} trades)",
                    available.size(), e);
            player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: picker send failed (see server log)."));
            return false;
        }
    }

    /**
     * Rebuild the villager's offers from profile state, preserving use-counts.
     *
     * A freshly generated MerchantOffer starts at zero uses, so replacing the live offers would
     * silently restock every used trade (e.g. on level-up). When a regenerated pick matches a live
     * offer (same result + base cost) the existing instance is kept, so uses and demand survive.
     * Legacy levels are matched by value too: the profile's copies are an import-time snapshot, and
     * using them would roll uses back to zero after a world reload.
     *
     * Reputation / Hero discounts are not applied here: they're per-player state written by
     * {@link #openMerchant} after the stale-session teardown.
     */
    static void applyToVillager(ServerLevel level, Villager villager, VillagerProfile profile) {
        int currentLevel = villager.getVillagerData().level();

        // Previously-live offers, drawn on to preserve uses/demand.
        List<MerchantOffer> carryOver = new ArrayList<>();
        MerchantOffers previous = villager.getOffers();
        if (previous != null) carryOver.addAll(previous);

        // Legacy claims its live instances FIRST, matched by value: after a reload the profile
        // decodes into fresh MerchantOffers, so identity finds nothing and the stale snapshots would
        // be listed (rolling back uses, possibly duplicating a trade). Claiming first also stops a
        // pick consuming a legacy instance and listing it twice.
        Map<Integer, List<MerchantOffer>> resolvedLegacy = new HashMap<>();
        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            List<MerchantOffer> stored = profile.legacyFor(lvl);
            if (stored.isEmpty()) continue;
            List<MerchantOffer> resolved = new ArrayList<>(stored.size());
            for (MerchantOffer snapshot : stored) {
                MerchantOffer live = takeMatching(carryOver, snapshot);
                resolved.add(live != null ? live : snapshot);
            }
            resolvedLegacy.put(lvl, resolved);
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
            offers.addAll(resolvedLegacy.getOrDefault(lvl, List.of()));
        }

        villager.setOffers(offers);
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
     * Re-apply reputation and Hero-of-the-Village discounts to the current offers. Vanilla only
     * does this from startTrading(), which we skip by opening the merchant manually.
     *
     * Call only from {@link #openMerchant}, after the stale-session teardown (see there).
     * updateSpecialPrices accumulates into specialPriceDiff, so clear each offer first or every
     * re-open would stack the discount.
     */
    private static void refreshSpecialPrices(Villager villager, ServerPlayer player) {
        if (player == null) return;
        MerchantOffers offers = villager.getOffers();
        if (offers == null) return;
        for (MerchantOffer offer : offers) {
            offer.resetSpecialPriceDiff();
        }
        ((VillagerInvoker) villager).tradeoptimizer$updateSpecialPrices(player);
    }
}
