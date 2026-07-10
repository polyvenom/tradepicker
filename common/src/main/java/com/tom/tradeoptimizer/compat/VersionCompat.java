package com.tom.tradeoptimizer.compat;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runtime compat shims for the 1.21.9 &ndash; 1.21.11 Fabric cluster.
 *
 * <p>1.21.11 is Mojang's pre-unobfuscation "great rename": nearly all of its API only changed
 * <em>deobfuscated names</em> (ResourceLocation&rarr;Identifier, the new {@code npc.villager}
 * subpackage, ResourceKey.location()&rarr;identifier(), &hellip;). Those are invisible to a
 * Fabric mod, which ships remapped to <em>intermediary</em> &mdash; the intermediary ids are
 * stable across 9/10/11 &mdash; so this jar, built against 1.21.9 mappings, loads and runs
 * unchanged on all three versions.
 *
 * <p>Exactly two APIs the mod calls changed their real bytecode signature in 1.21.11, so the
 * old intermediary this jar references is genuinely gone there. Both are dispatched reflectively
 * so a single jar covers the whole cluster:
 * <ol>
 *   <li>{@link VillagerTrades.ItemListing#getOffer} gained a leading {@link ServerLevel}
 *       parameter: {@code (Entity, RandomSource)} &rarr; {@code (ServerLevel, Entity, RandomSource)}.</li>
 *   <li>{@code Entity.hasPermissions(int)} was removed when integer permission <em>levels</em>
 *       became the {@code PermissionSet}/{@code PermissionLevel} model. The level ids are
 *       unchanged (ALL=0 &hellip; GAMEMASTERS=2 &hellip; OWNERS=4), and {@code PermissionLevel}
 *       is an enum whose {@code ordinal()} equals those ids &mdash; so the check needs no
 *       renamed symbol at all.</li>
 * </ol>
 *
 * <p>1.21.11 is the final obfuscated Minecraft release, so these shapes are frozen. Nothing here
 * relies on an (obfuscated) name: getOffer is resolved by method <em>shape</em>, and the
 * permission path probes the legacy call and only walks the new model once it is proven absent
 * &mdash; both work identically under the dev (named) and production (intermediary) runtimes.
 */
public final class VersionCompat {
    private VersionCompat() {}

    // ---- VillagerTrades.ItemListing#getOffer --------------------------------

    /** The single abstract {@code getOffer} of ItemListing, resolved once at class load. */
    private static final Method GET_OFFER = resolveGetOffer();
    /** True on 1.21.11+, where getOffer takes a leading {@link ServerLevel}. */
    private static final boolean GET_OFFER_TAKES_LEVEL = GET_OFFER != null && GET_OFFER.getParameterCount() == 3;

    private static Method resolveGetOffer() {
        for (Method m : VillagerTrades.ItemListing.class.getMethods()) {
            if (!m.isSynthetic() && m.getReturnType() == MerchantOffer.class) return m;
        }
        return null;
    }

    /**
     * Invoke {@link VillagerTrades.ItemListing#getOffer} across versions. Pre-1.21.11 the
     * signature is {@code (Entity, RandomSource)}; 1.21.11 prepends a {@link ServerLevel}.
     * The listing's own exceptions are rethrown unwrapped so callers' existing try/catch
     * (which skips a listing on failure, mirroring vanilla) behaves identically.
     */
    public static MerchantOffer getOffer(VillagerTrades.ItemListing listing, ServerLevel level,
                                         Villager villager, RandomSource random) {
        if (GET_OFFER == null) {
            throw new IllegalStateException("Could not resolve VillagerTrades.ItemListing#getOffer");
        }
        try {
            Object offer = GET_OFFER_TAKES_LEVEL
                    ? GET_OFFER.invoke(listing, level, villager, random)
                    : GET_OFFER.invoke(listing, villager, random);
            return (MerchantOffer) offer;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ItemListing#getOffer reflective dispatch failed", e);
        }
    }

    // ---- permission level (op check) ----------------------------------------

    /** Flipped true once we learn {@code Entity#hasPermissions(int)} is gone (1.21.11). */
    private static volatile boolean legacyPermGone = false;
    /** 1.21.11's {@code Entity#permissions()} accessor, resolved lazily by shape. */
    private static volatile Method permissionsMethod = null;

    /**
     * Whether {@code player} holds at least the given vanilla permission level (2 = the /op
     * cheat-command threshold). Tries the 1.21.9/1.21.10 integer {@code hasPermissions(int)}
     * first; if that method is gone (1.21.11) it walks the new PermissionSet/PermissionLevel
     * model, whose level ordinals equal the old integer levels. Denies (false) if the modern
     * shape can't be walked, rather than crashing.
     */
    public static boolean hasPermissionLevel(ServerPlayer player, int level) {
        if (!legacyPermGone) {
            try {
                return player.hasPermissions(level);
            } catch (LinkageError e) {
                // 1.21.11 removed Entity#hasPermissions(int) (a NoSuchMethodError, a LinkageError)
                // — cache and use the modern PermissionSet model from here on.
                legacyPermGone = true;
            }
        }
        return modernPermissionLevel(player, level);
    }

    private static boolean modernPermissionLevel(ServerPlayer player, int level) {
        try {
            Method accessor = permissionsMethod;
            if (accessor == null) {
                accessor = resolvePermissionsMethod();
                if (accessor == null) return false;
                permissionsMethod = accessor;
            }
            Object permissionSet = accessor.invoke(player);                 // 1.21.11 PermissionSet
            Method levelGetter = noArgEnumGetter(permissionSet.getClass());  // PermissionLevel level()
            if (levelGetter == null) return false;
            Object permissionLevel = levelGetter.invoke(permissionSet);
            return permissionLevel instanceof Enum<?> e && e.ordinal() >= level;
        } catch (ReflectiveOperationException e) {
            TradeOptimizer.LOGGER.warn("[compat] 1.21.11 permission check failed, denying: {}", e.toString());
            return false;
        }
    }

    /**
     * Find 1.21.11's {@code Entity#permissions()} by shape: a no-arg method returning a
     * {@code PermissionSet}-like interface (a {@code boolean hasPermission(obj)} plus a
     * self-typed {@code union(self)}). Only ever reached once we know we are on 1.21.11, so it
     * cannot mis-fire on the older versions where no such method exists.
     */
    private static Method resolvePermissionsMethod() {
        for (Method m : ServerPlayer.class.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (!rt.isInterface()) continue;
            if (looksLikePermissionSet(rt)) return m;
        }
        return null;
    }

    private static boolean looksLikePermissionSet(Class<?> type) {
        boolean hasPermissionQuery = false; // boolean hasPermission(Permission)  — object param
        boolean hasUnion = false;           // PermissionSet union(PermissionSet)
        for (Method m : type.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && m.getReturnType() == boolean.class && !params[0].isPrimitive()) {
                hasPermissionQuery = true;
            }
            if (params.length == 1 && type.isAssignableFrom(m.getReturnType())
                    && type.isAssignableFrom(params[0])) {
                hasUnion = true;
            }
        }
        return hasPermissionQuery && hasUnion;
    }

    /** The no-arg method returning an enum (LevelBasedPermissionSet#level()). */
    private static Method noArgEnumGetter(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().isEnum()) return m;
        }
        return null;
    }
}
