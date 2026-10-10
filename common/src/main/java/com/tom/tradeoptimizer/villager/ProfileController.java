package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.api.TradePickerApi;
import com.tom.tradeoptimizer.api.VillagerInteractionHandler;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerTrades;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

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

    /** Op-equivalent: command permission level 2 (GAMEMASTERS). */
    private static final Permission OP_PERMISSION =
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(OP_PERMISSION);
    }

    /** Reach leeway past the player's entity interaction range — what vanilla allows an open container. */
    private static final double REACH_LEEWAY = 4.0;

    /**
     * The trust gate every player action on a villager passes: the right-click path and both
     * packet handlers (#9). A modified client can send a submit or reset for any villager UUID, so
     * the server re-checks what vanilla interaction guarantees: alive, same level, within reach,
     * not mid-trade with someone else.
     *
     * Package-private for ActionGateGameTest.
     */
    static boolean canAct(ServerPlayer player, Villager villager) {
        if (!villager.isAlive() || villager.level() != player.level()) return false;
        if (!player.isWithinEntityInteractionRange(villager, REACH_LEEWAY)) return false;
        var trader = villager.getTradingPlayer();
        return trader == null || trader == player;
    }

    /**
     * Clicks the mod hands straight to vanilla, so vanilla does exactly what it always does with
     * them (issue #10):
     *  - the two item interactions vanilla settles before trading (a named name tag, a matching
     *    spawn egg). Anything else stays with us: a blanket pass-through (e.g. on sneak) would drop
     *    the player into vanilla's own trade window, whose random rolls would then compete with the
     *    picker's trades on the same villager;
     *  - villagers that can't trade at all (nitwits, the unemployed);
     *  - the clicks vanilla refuses: a baby (it shakes its head), a sleeping villager, one that's
     *    dying or busy trading with someone else (canAct).
     *
     * Package-private for VanillaInteractionGateGameTest: onInteract itself needs a modded client.
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

    /**
     * Players without the mod on their client who we've already nudged once, so the fallback
     * notice doesn't repeat on every right-click.
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
        int merchantLevel = data.level();
        VillagerProfession prof = data.profession().value();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getResourceKey(prof)
                .map(k -> k.identifier().toString()).orElse("minecraft:none");

        if (leaveToVanilla(player, villager)) {
            return true; // PASS to vanilla
        }

        // Add-on takeover hook: a handler that claims this villager replaces the picker flow.
        for (VillagerInteractionHandler handler : TradePickerApi.interactionHandlers()) {
            if (handler.onInteract(player, villager) == VillagerInteractionHandler.Result.TAKEOVER) {
                return false; // add-on handled it; the listener returns SUCCESS
            }
        }

        // Without the mod on the client we can't show the picker, and swallowing the click would
        // leave the villager inert, so fall back to vanilla trading (nudging the player once).
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

        // Case 2: no profile yet.
        //   2a truly fresh (level 1, zero XP): vanilla's starter rolls are untouched, so discard
        //      them and open the picker.
        //   2b pre-existing (level 2+, or level 1 with XP): import what's there as legacy, then
        //      open the merchant.
        if (profile == null) {
            // First interaction claims ownership (owner/op only may reset later).
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
                Services.PLATFORM.setVillagerOffers(villager, new MerchantOffers());
                TradeOptimizer.LOGGER.info("Truly fresh villager {} — discarding vanilla starter rolls, opening picker (owner={})",
                        villager.getUUID(), player.getName().getString());
            }
            state.update(profile);
        } else if (!profile.profession().equals(profName)) {
            // Profession changed: start over, keeping the owner.
            UUID keepOwner = profile.owner().orElse(player.getUUID());
            profile = VillagerProfile.fresh(villager.getUUID(), profName, keepOwner);
            state.update(profile);
        } else if (profile.owner().isEmpty()) {
            // Profile saved before ownership existed: first right-click claims it.
            profile = profile.withOwner(player.getUUID());
            state.update(profile);
            TradeOptimizer.LOGGER.info("Claimed ownership of grandfathered villager {} for {}",
                    villager.getUUID(), player.getName().getString());
        }

        // A rank can be earned and already picked but never granted (older saves). Grant it
        // before anything reads the rank.
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
        // can arrive here several levels ahead of its picks.
        int unfilled = firstUnfilledLevel(profile, merchantLevel);
        if (unfilled > 0 && sendPicker(player, villager, profile, unfilled)) {
            return false;
        }

        // All current levels are picked. If the next rank is earned, vanilla's level-up is held
        // (VillagerLevelUpMixin) until its trades are chosen, so offer that picker now.
        if (isDueToLevelUp(villager)
                && !profile.isFilled(merchantLevel + 1)
                && sendPicker(player, villager, profile, merchantLevel + 1)) {
            return false;
        }

        // Don't rebuild live offers on every open: regenerating resets each trade's use-count and
        // bypasses vanilla's restock cooldown. Rebuild only if they're missing.
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
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getResourceKey(prof)
                .map(k -> k.identifier().toString()).orElse("minecraft:none");

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

        // The picks list comes from the client and is untrusted: without these checks a modified
        // client could get any trade id, or a book key for any enchantment, built at minimum cost.

        // 1) The level must be one the villager has reached. One level above is legitimate only
        //    while vanilla's level-up is held for it; anything beyond is a tampered packet.
        int currentLevel = villager.getVillagerData().level();
        boolean levellingUp = level == currentLevel + 1 && isDueToLevelUp(villager);
        if (level < 1 || (level > currentLevel && !levellingUp)) {
            TradeOptimizer.LOGGER.warn("[submit] rejected out-of-range level {} for villager {} (at level {})",
                    level, villagerId, currentLevel);
            return;
        }

        // 2) Every pick must be in this (profession, level) pool, re-enumerated server-side.
        List<AvailableTrade> available;
        try {
            available = enumeratePool(sl, villager, level);
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

        // Picks allowed = vanilla's per-level trade count, as sent to the client. Trim extras
        // (e.g. several gear variants of one listing).
        int picksRequired = Math.max(1, perLevelTradeCount(sl, villager.getVillagerData().profession().value(), level));
        if (validatedPicks.size() > picksRequired) {
            TradeOptimizer.LOGGER.warn("[submit] {} picks exceed allowed {} for {} level {} — trimming",
                    validatedPicks.size(), picksRequired, profName, level);
            validatedPicks = new ArrayList<>(validatedPicks.subList(0, picksRequired));
        }
        // And no fewer: a picked level is locked, so an under-filled submit would stay short for good.
        if (validatedPicks.size() < Math.min(picksRequired, available.size())) {
            TradeOptimizer.LOGGER.warn("[submit] rejected: {} picks, {} required for {} level {}",
                    validatedPicks.size(), picksRequired, profName, level);
            player.sendSystemMessage(Component.literal(
                    "Trade Picker: those trades aren't valid for this villager."));
            return;
        }

        // 3) Enforce the per-level book cap (vanillaBookLimits) with the same bookPickCap as
        //    sendPicker, computed from the card list the client actually saw (hidePickedTrades
        //    filtering included) so a legitimate submit is never rejected.
        List<AvailableTrade> shownToClient = profile == null ? available
                : effectiveAvailable(available, ownedTradeKeys(profile, available), picksRequired);
        int bookCap = bookPickCap(sl, villager, level, shownToClient, picksRequired);
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

        // Several levels can be banked: chain into the next picker instead of opening the
        // merchant, or the skipped levels' trades are lost for good.
        int nextUnfilled = firstUnfilledLevel(profile, currentLevel);
        if (nextUnfilled > 0 && sendPicker(player, villager, profile, nextUnfilled)) {
            return;
        }

        openMerchant(villager, player, level);
    }

    /**
     * Called after any mob conversion.
     *
     * Vanilla's Mob.convertTo discards the old entity and never copies the UUID, and profiles are
     * keyed by villager UUID. Without re-keying, a zombify-cure round trip orphans the profile: the
     * cured villager keeps its offers (ZombieVillager.finishConversion copies them) but loses its
     * picks and owner, and the next right-click imports them as permanent "legacy" offers.
     * Re-keying on every conversion carries the profile through villager -> zombie -> cured.
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

        // Reset is destructive: owner only; ops bypass.
        VillagerProfileState state = VillagerProfileState.get(sl);
        VillagerProfile profile = state.get(villagerId);

        boolean op = isOp(player);

        if (profile == null) {
            // Never touched by the mod: reject so a crafted packet can't wipe a vanilla villager.
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
            // Keep the owner across reset.
            UUID keepOwner = profile.owner().orElse(player.getUUID());
            String profName = profile.profession();
            profile = VillagerProfile.fresh(villagerId, profName, keepOwner);
            state.update(profile);
        }

        VillagerData current = villager.getVillagerData();
        villager.setVillagerData(current.withLevel(1));
        villager.setVillagerXp(0);
        Services.PLATFORM.setVillagerOffers(villager, new MerchantOffers());

        // The client only swaps screens on reset; the server-side MerchantMenu and tradingPlayer
        // linger. The next open would then close that stale menu mid-openMenu, its removed() would
        // null tradingPlayer, and the new menu would fail stillValid() and close after one frame.
        // Tear it down now. Only THIS villager's session (#9): canAct guarantees it trades with
        // this player or no one.
        if (villager.getTradingPlayer() == player) {
            if (player.containerMenu instanceof MerchantMenu) player.closeContainer();
            villager.setTradingPlayer(null);
        }

        player.sendSystemMessage(Component.literal(
                "Villager reset to Novice. Right-click to pick new trades."));
    }

    /**
     * Open the merchant menu: tear down any stale trade session, THEN refresh the per-player
     * discounts, THEN open. The order is load-bearing, and this must stay idempotent.
     *
     * The vanilla client sends two packets per right-click (interact_at, interact) and the loader
     * event fires for both, so onInteract runs twice. On the second run the open container is the
     * MerchantMenu the first run created. {@code ServerPlayer.openMenu} closes the current
     * container after we've set the trading player, and a leftover MerchantMenu's {@code removed()}
     * calls {@code setTradingPlayer(null)}. {@code stillValid()} is
     * {@code merchant.getTradingPlayer() == player}, so the new menu then closes after one frame.
     * Tearing the stale session down first leaves openMenu nothing to close.
     *
     * The discount refresh must come after the teardown: that {@code setTradingPlayer(null)} runs
     * vanilla's {@code resetSpecialPrices()} and zeroes every specialPriceDiff, so an earlier
     * refresh is wiped. {@code setTradingPlayer(player)} with a non-null player doesn't reset them.
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
     * smaller pool yields fewer, e.g. a toolsmith's master level; data packs can change it too).
     * So each level's trade-set size (capped at vanilla's maximum) walks the flat list, and the
     * current (highest) level takes everything left so no offer is lost.
     *
     * Package-private for the legacy-bucketing gametest: onInteract is gated behind a
     * networking check a mock player can't satisfy.
     */
    static void importExistingOffers(ServerLevel level, Villager villager,
                                             VillagerProfile profile, MerchantOffers existing, int merchantLevel) {
        VillagerProfession prof = villager.getVillagerData().profession().value();

        int total = existing.size();
        int idx = 0;
        for (int lvl = 1; lvl <= merchantLevel && idx < total; lvl++) {
            int count;
            if (lvl == merchantLevel) {
                // Current level takes everything still unassigned.
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

    /**
     * Mirrors {@code Mob.checkAndHandleImportantInteractions}: the only two item interactions
     * vanilla settles on a mob before trading is ever considered.
     *
     * Deliberately narrower than "is a name tag / spawn egg": an unnamed tag or a mismatched egg
     * does nothing in vanilla, which then opens the trade screen, so passing those along would
     * land the player in the vanilla merchant UI instead of the picker.
     *
     * Package-private for VanillaInteractionGateGameTest.
     */
    static boolean isVanillaItemInteraction(ItemStack stack, Villager villager) {
        if (stack.is(Items.NAME_TAG)) {
            return stack.has(DataComponents.CUSTOM_NAME);
        }
        // 1.21.11: spawnsEntity is an instance method on SpawnEggItem (static on 26.x).
        return stack.getItem() instanceof SpawnEggItem egg && egg.spawnsEntity(stack, villager.getType());
    }

    /**
     * The last picker each player was sent. onInteract runs twice per click (see openMerchant), and
     * two payloads make the client build the screen twice and blink.
     */
    private record PickerSend(UUID villagerId, int merchantLevel, long gameTime) {}

    private static final Map<UUID, PickerSend> lastPickerSend = new HashMap<>();

    /** Two runs of the same click land in the same tick, or within one of it under load. */
    private static final long DUPLICATE_CLICK_TICKS = 2;

    /**
     * What a villager's trade pool depends on: merchant level, profession and biome type, enabled
     * feature flags (the trade rebalance swaps the table), the registries, and the config.
     */
    private record PoolKey(int merchantLevel, Holder<VillagerProfession> profession, Holder<VillagerType> type,
                           FeatureFlagSet features, HolderLookup.Provider registries, int configRevision) {}

    /** The last pool enumerated for a villager; {@code gameTime} is when, for expiry. */
    private record PoolEntry(PoolKey key, long gameTime, List<AvailableTrade> pool) {}

    /**
     * The last trade pool enumerated per villager, reused by a click's second run and by the
     * submit's validation (#20). Enumeration is expensive (headline gear re-rolls enchanting up to
     * 256 times per enchantment). Validating against the list the picker was built from is also
     * the stricter check.
     */
    private static final Map<UUID, PoolEntry> poolCache = new HashMap<>();

    /** Ten minutes: long enough for a player to read the picker, short enough to bound the map. */
    private static final long POOL_CACHE_TICKS = 12_000;

    /** {@link OfferFactory#enumerate}, cached per villager. The list is unmodifiable: it's shared. */
    static List<AvailableTrade> enumeratePool(ServerLevel level, Villager villager, int merchantLevel) {
        long now = level.getGameTime();
        HolderLookup.Provider registries = level.registryAccess();
        VillagerData data = villager.getVillagerData();
        PoolKey key = new PoolKey(merchantLevel, data.profession(), data.type(), level.enabledFeatures(),
                registries, TradeOptimizerConfig.revision());
        PoolEntry hit = poolCache.get(villager.getUUID());
        if (hit != null && hit.key().equals(key) && now >= hit.gameTime()
                && now - hit.gameTime() <= POOL_CACHE_TICKS) {
            return hit.pool();
        }
        List<AvailableTrade> pool = List.copyOf(OfferFactory.enumerate(level, villager, merchantLevel));
        // Drop expired entries and any left over from another world.
        poolCache.values().removeIf(e -> e.key().registries() != registries
                || now < e.gameTime() || now - e.gameTime() > POOL_CACHE_TICKS);
        poolCache.put(villager.getUUID(), new PoolEntry(key, now, pool));
        return pool;
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
     * With {@code vanillaBookLimits} OFF this is {@code picksRequired} (no limit). With it ON it's
     * vanilla's per-level book-trade count ({@link OfferFactory#countBookTemplates}, usually 1),
     * raised when there aren't enough non-book options to fill the level (no softlock on an
     * all-books pool). Server and client compute this identically.
     */
    private static int bookPickCap(ServerLevel level, Villager villager, int merchantLevel,
                                   List<AvailableTrade> available, int picksRequired) {
        if (!TradeOptimizerConfig.get().vanillaBookLimits()) return picksRequired;
        int nonBookCards = 0;
        for (AvailableTrade t : available) {
            if (!OfferFactory.isBookKey(t.key())) nonBookCards++;
        }
        // No book trades in this pool: nothing to cap (and no "(max 0 book)" hint).
        if (nonBookCards == available.size()) return picksRequired;
        int cap = Math.min(OfferFactory.countBookTemplates(level, villager, merchantLevel), picksRequired);
        cap = Math.max(cap, picksRequired - nonBookCards); // never make the level impossible to fill
        return Math.min(cap, picksRequired);
    }

    /**
     * The TradeKeys within {@code available} that this villager already sells: any key the
     * profile has picked at ANY level, plus any card whose preview result matches a legacy
     * (imported vanilla) offer's result. Synthetic book/gear/arrow keys are merchant-level
     * independent, so an earlier-level book pick matches the identical card at this level;
     * flat listing keys are level-scoped and never collide across levels (issue #7).
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
     * The card list the picker actually shows. With hidePickedTrades OFF (default) this is the
     * full pool — owned cards are only marked client-side. With it ON, owned cards are removed,
     * UNLESS that would leave fewer cards than the level needs picked (then the full pool is
     * kept so the level can still be filled). Returns {@code available} (same instance) when
     * nothing was filtered, so callers can detect filtering by identity.
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
     * Grant every held rank whose picks are already stored, then rebuild the offers.
     *
     * Older saves can have a rank earned and picked but never granted. The picker treats it as
     * done, and applyToVillager only walks levels up to the current rank, so the villager would
     * stay a rank short for good. Grants only ranks both earned and picked.
     *
     * Package-private for LevelUpHoldGameTest.
     */
    static void grantRanksAlreadyPicked(ServerLevel level, Villager villager, VillagerProfile profile) {
        int before = villager.getVillagerData().level();
        while (isDueToLevelUp(villager)
                && profile.isFilled(villager.getVillagerData().level() + 1)
                && grantPendingLevel(villager, villager.getVillagerData().level() + 1)) {
            // loop: the XP may cover another rank
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
     * rank granted afterwards would have its trades left out of the offer list.
     *
     * Package-private for LevelUpHoldGameTest.
     */
    static boolean grantPendingLevel(Villager villager, int level) {
        if (level != villager.getVillagerData().level() + 1 || !isDueToLevelUp(villager)) return false;
        villager.setVillagerData(villager.getVillagerData().withLevel(level));
        return true;
    }

    /**
     * Vanilla's own {@code shouldIncreaseLevel} condition, recomputed here because the level-up it
     * guards is held by VillagerLevelUpMixin until the player has picked.
     *
     * Deliberately the same two checks vanilla makes - the rank can still rise, and the XP earned
     * has reached the bar for it - so a villager is offered the next rank at exactly the moment
     * vanilla would have granted it.
     *
     * Package-private for LevelUpHoldGameTest.
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
     * Scanning from the bottom rather than looking only at {@code currentLevel} is what keeps a
     * multi-level jump from silently dropping a level's trades.
     *
     * Package-private for LevelCatchUpGameTest.
     */
    static int firstUnfilledLevel(VillagerProfile profile, int currentLevel) {
        for (int lvl = 1; lvl <= currentLevel; lvl++) {
            if (!profile.isFilled(lvl)) return lvl;
        }
        return -1;
    }

    /**
     * @return true when this call took responsibility for what the player sees next - the picker
     *         payload was sent, or the level auto-progressed and the merchant was opened here.
     *         false means nothing was shown and the caller should fall back to opening the merchant.
     */
    static boolean sendPicker(ServerPlayer player, Villager villager, VillagerProfile profile, int merchantLevel) {
        ServerLevel level = player.level();

        List<AvailableTrade> available;
        try {
            available = enumeratePool(level, villager, merchantLevel);
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

        // Picks required = trades vanilla grants at this level (listing count, capped at 2), not
        // card count: gear and arrows expand one listing into many cards.
        int picksRequired = Math.max(1, perLevelTradeCount(level, villager.getVillagerData().profession().value(), merchantLevel));

        // No-choice fast path: when the expanded pool has no more cards than picks required there's
        // nothing to choose, so skip the picker and apply every card. A single listing that DID
        // expand (many enchant/potion cards) has more cards than picks, so it still shows the picker.
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

            // Another level may still be unpicked: carry on down the chain.
            int currentLevel = villager.getVillagerData().level();
            int next = firstUnfilledLevel(profile, currentLevel);
            if (next > 0 && next != merchantLevel) {
                return sendPicker(player, villager, profile, next);
            }
            openMerchant(villager, player, currentLevel);
            return true;
        }

        // Duplicate of the same click: payload already sent; report handled.
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

        // #7: trades already on this villager are marked (default) or removed (hidePickedTrades).
        List<TradeKey> ownedKeys = ownedTradeKeys(profile, available);
        List<AvailableTrade> shown = effectiveAvailable(available, ownedKeys, picksRequired);
        boolean hidOwned = shown != available;

        int maxBookPicks = bookPickCap(level, villager, merchantLevel, shown, picksRequired);

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
     * Rebuild the villager's offers from profile state, preserving use-counts on trades that
     * carry over from the previous offer list.
     *
     * A freshly generated MerchantOffer has zero uses, so replacing the live offers would restock
     * every used trade on each level-up. A regenerated pick that matches a live offer (same result
     * and base cost) keeps the live instance, preserving uses and demand.
     *
     * Legacy levels are matched by value the same way, with the stored copy as fallback: the
     * profile's copies are an import-time snapshot, so using them would reset uses after a reload.
     *
     * Reputation / Hero discounts are NOT applied here; {@link #openMerchant} writes them just
     * before the menu opens.
     *
     * Package-private for the profile-persistence gametest.
     */
    static void applyToVillager(ServerLevel level, Villager villager, VillagerProfile profile) {
        int currentLevel = villager.getVillagerData().level();

        List<MerchantOffer> carryOver = new ArrayList<>();
        MerchantOffers previous = villager.getOffers();
        if (previous != null) carryOver.addAll(previous);

        // Legacy claims its live instances FIRST, by value, not identity: after a reload the
        // profile decodes into fresh MerchantOffers, so identity finds nothing and stale snapshots
        // get listed (free restock, possible duplicate). Claiming first also stops a pick from
        // consuming a legacy instance.
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

        Services.PLATFORM.setVillagerOffers(villager, offers);
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
     * calls Villager.updateSpecialPrices from startTrading(), which our manual open skips.
     *
     * Called ONLY from {@link #openMerchant}, after the stale-session teardown (see there).
     * updateSpecialPrices accumulates into specialPriceDiff, so each offer is cleared first or
     * re-opening would stack the discount.
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
