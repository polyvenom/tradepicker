package com.tom.tradeoptimizer.client;

import com.tom.tradeoptimizer.client.access.MerchantScreenAccessor;
import com.tom.tradeoptimizer.client.keybind.TradeOptimizerKeybinds;
import com.tom.tradeoptimizer.client.net.ClientNetworkHandler;
import com.tom.tradeoptimizer.client.state.ClientTradeState;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.StartCycleC2S;
import com.tom.tradeoptimizer.network.StopCycleC2S;
import com.tom.tradeoptimizer.trade.CycleController;
import com.tom.tradeoptimizer.trade.TradeSignature;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.UUID;

public final class TradeOptimizerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientNetworkHandler.register();
        TradeOptimizerKeybinds.register();

        // Outside-screen path: cancel works even if the player closed the merchant
        // window during a cycle. Cycle-for-selected outside a screen does nothing.
        ClientTickEvents.END_CLIENT_TICK.register(TradeOptimizerClient::onClientTick);

        // In-screen path: when a MerchantScreen opens, attach a key listener so the
        // cycle / cancel binds work while the trade GUI is up. KeyMapping.consumeClick()
        // never fires inside a Screen because the screen swallows keyboard input.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof MerchantScreen merchant)) return;
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, keyEvent) -> {
                // Don't fire if the user is typing in a focused EditBox (e.g. SortMod's search).
                if (s.getFocused() instanceof EditBox) return;
                if (TradeOptimizerKeybinds.cycleForSelected.matches(keyEvent)) {
                    handleCycleForSelected(client, merchant);
                } else if (TradeOptimizerKeybinds.cancelCycle.matches(keyEvent)) {
                    sendStop();
                }
            });
        });
    }

    private static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        // Cancel works outside merchant screen too.
        while (TradeOptimizerKeybinds.cancelCycle.consumeClick()) {
            sendStop();
        }
        // Drain the cycle keybind so it doesn't spuriously fire later if pressed outside.
        while (TradeOptimizerKeybinds.cycleForSelected.consumeClick()) {
            // No-op outside a merchant screen.
        }
    }

    private static void sendStop() {
        if (ClientPlayNetworking.canSend(NetworkPayloads.STOP_CYCLE_TYPE)) {
            ClientPlayNetworking.send(StopCycleC2S.INSTANCE);
        }
    }

    private static void handleCycleForSelected(Minecraft client, MerchantScreen merchant) {
        if (client.player == null) return;
        if (!ClientPlayNetworking.canSend(NetworkPayloads.START_CYCLE_TYPE)) {
            client.player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: server doesn't have the mod installed."));
            return;
        }

        VillagerEntry snapshot = ClientTradeState.snapshot().orElse(null);
        if (snapshot == null) {
            client.player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: no villager snapshot — close and reopen the trade screen."));
            return;
        }

        int selected = ((MerchantScreenAccessor) merchant).tradeoptimizer$getShopItem();
        var offers = merchant.getMenu().getOffers();
        if (offers == null || offers.isEmpty()) {
            client.player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: no trades to target."));
            return;
        }
        if (selected < 0 || selected >= offers.size()) selected = 0;

        MerchantOffer chosen = offers.get(selected);
        TradeSignature target = TradeSignature.of(chosen.getResult());

        BlockPos workstation = findNearbyWorkstation(client, snapshot);
        if (workstation == null) {
            client.player.sendSystemMessage(Component.literal(
                    "Trade Optimizer: no workstation found near the villager."));
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
