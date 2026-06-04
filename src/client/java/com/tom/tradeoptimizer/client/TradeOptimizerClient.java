package com.tom.tradeoptimizer.client;

import com.tom.tradeoptimizer.client.net.ClientNetworkHandler;
import com.tom.tradeoptimizer.client.state.ClientLastVillager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

public final class TradeOptimizerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientNetworkHandler.register();

        // Remember which villager the player just clicked on, so the Reset button in
        // the merchant screen knows which UUID to ask the server to reset.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && entity instanceof Villager v) {
                ClientLastVillager.set(v.getUUID());
            }
            return InteractionResult.PASS;
        });
    }
}
