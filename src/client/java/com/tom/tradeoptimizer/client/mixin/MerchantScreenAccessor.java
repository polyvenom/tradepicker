package com.tom.tradeoptimizer.client.mixin;

/**
 * Implemented by our MerchantScreenMixin so the cycle keybind can read
 * the currently-selected trade index without going through reflection.
 */
public interface MerchantScreenAccessor {
    int tradeoptimizer$getShopItem();
}
