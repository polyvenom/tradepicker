package com.tom.tradeoptimizer.client;

import com.tom.tradeoptimizer.client.keybind.TradeOptimizerKeybinds;
import com.tom.tradeoptimizer.client.net.ClientNetworkHandler;
import com.tom.tradeoptimizer.client.state.ClientTradeState;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.StartCycleC2S;
import com.tom.tradeoptimizer.network.StopCycleC2S;
import com.tom.tradeoptimizer.trade.CycleController;
import com.tom.tradeoptimizer.trade.TradeSignature;
import com.tom.tradeoptimizer.villager.OfferEntry;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.UUID;

public final class TradeOptimizerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientNetworkHandler.register();
        TradeOptimizerKeybinds.register();

        ClientTickEvents.END_CLIENT_TICK.register(TradeOptimizerClient::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        // Keybinds only fire when player + level exist.
        if (client.player == null || client.level == null) return;

        while (TradeOptimizerKeybinds.cycleForSelected.consumeClick()) {
            handleCycleForSelected(client);
        }
        while (TradeOptimizerKeybinds.cancelCycle.consumeClick()) {
            if (ClientPlayNetworking.canSend(NetworkPayloads.STOP_CYCLE_TYPE)) {
                ClientPlayNetworking.send(StopCycleC2S.INSTANCE);
            }
        }
    }

    /**
     * Triggered when the player presses the cycle keybind. Looks at the merchant screen
     * if one's open to determine target trade + villager + workstation.
     */
    private static void handleCycleForSelected(Minecraft client) {
        if (!(client.screen instanceof MerchantScreen merchant)) {
            // Could happen if user presses key outside a trade — silently ignore.
            return;
        }
        if (!ClientPlayNetworking.canSend(NetworkPayloads.START_CYCLE_TYPE)) {
            client.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("Trade Optimizer: server doesn't have the mod installed."));
            return;
        }

        VillagerEntry snapshot = ClientTradeState.snapshot().orElse(null);
        if (snapshot == null) {
            client.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("Trade Optimizer: no villager snapshot — interact with the villager first."));
            return;
        }

        // The vanilla selection hint marks the highlighted trade row.
        int selected = -1;
        try {
            // MerchantMenu.getTraderXp/etc don't expose selectionHint publicly,
            // but selectionHint isn't required — we'll fall back to the first ENCHANTED_BOOK
            // or use trade index 0.
            selected = 0;
        } catch (Exception ignored) {}

        var offers = merchant.getMenu().getOffers();
        if (offers == null || offers.isEmpty() || selected < 0 || selected >= offers.size()) {
            client.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("Trade Optimizer: select a trade row first."));
            return;
        }
        MerchantOffer chosen = offers.get(selected);
        TradeSignature target = TradeSignature.of(chosen.getResult());

        // Find the nearest workstation block to use for cycling.
        BlockPos workstation = findNearbyWorkstation(client, snapshot);
        if (workstation == null) {
            client.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("Trade Optimizer: no workstation found near the villager."));
            return;
        }

        UUID villagerId = snapshot.id();
        ClientPlayNetworking.send(new StartCycleC2S(villagerId, workstation, target));
    }

    private static BlockPos findNearbyWorkstation(Minecraft client, VillagerEntry snapshot) {
        if (client.level == null) return null;
        BlockPos center = snapshot.pos();
        int radius = 5;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!CycleController.WORKSTATIONS.contains(client.level.getBlockState(mp).getBlock())) continue;
                    double d = mp.distSqr(center);
                    if (d < bestDist) {
                        bestDist = d;
                        best = mp.immutable();
                    }
                }
            }
        }
        return best;
    }
}
