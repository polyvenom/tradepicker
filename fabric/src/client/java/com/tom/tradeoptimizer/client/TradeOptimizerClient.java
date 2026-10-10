package com.tom.tradeoptimizer.client;

import com.tom.tradeoptimizer.client.net.ClientNetworkHandler;
import com.tom.tradeoptimizer.client.state.ClientLastVillager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

public final class TradeOptimizerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientNetworkHandler.register();

        // Remember the clicked villager for the Reset button.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) {
                if (entity instanceof Villager v) {
                    ClientLastVillager.set(v.getUUID());
                } else {
                    // Wandering Trader or anything else: clear the Reset target.
                    ClientLastVillager.clear();
                }
            }
            return InteractionResult.PASS;
        });

        // A villager from another world or server never owns a merchant screen (#18).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientLastVillager.clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientLastVillager.clear());
    }
}
