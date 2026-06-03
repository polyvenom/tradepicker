package com.tom.tradeoptimizer.client.access;

/**
 * Implemented by our MerchantScreenMixin so the cycle keybind can read the
 * currently-selected trade index without reflection.
 *
 * Lives outside the mixin package because Mixin's loader treats everything in
 * `com.tom.tradeoptimizer.client.mixin.*` as internal — direct references from
 * regular code are illegal.
 */
public interface MerchantScreenAccessor {
    int tradeoptimizer$getShopItem();
}
