package com.tom.tradeoptimizer.client;

import com.tom.tradeoptimizer.client.net.ClientNetworkHandler;
import com.tom.tradeoptimizer.client.state.ClientLastVillager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;

public final class TradeOptimizerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientNetworkHandler.register();

        // Remember which villager the player just clicked on, so the Reset button in
        // the merchant screen knows which UUID to ask the server to reset.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) {
                if (entity instanceof Villager v) {
                    ClientLastVillager.set(v.getUUID());
                } else {
                    // Wandering Trader (or anything else): if a merchant screen opens, it
                    // isn't a villager's, so clear the Reset button's target so it can't
                    // fire on a villager the player clicked earlier.
                    ClientLastVillager.clear();
                }
            }
            return InteractionResult.PASS;
        });
    }
}
