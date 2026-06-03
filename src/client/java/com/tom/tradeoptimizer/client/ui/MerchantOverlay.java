package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.client.state.ClientTradeState;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.network.CycleStatusS2C;
import com.tom.tradeoptimizer.trade.BaselinePrices;
import com.tom.tradeoptimizer.trade.TradeRating;
import com.tom.tradeoptimizer.trade.TradeSignature;
import com.tom.tradeoptimizer.villager.OfferEntry;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the trade-rating overlay on top of the vanilla merchant screen.
 *
 * Layout (matches vanilla MerchantScreen offers panel):
 *   First trade row Y = topPos + 17
 *   Each row height  = 20
 *   Row X range      = leftPos + 5  .. leftPos + 94
 *   We draw chips at right edge of each row (leftPos + 95..104).
 *
 * The cycle-status banner is drawn at the top of the screen, centered.
 */
public final class MerchantOverlay {
    private MerchantOverlay() {}

    private static final int ROW_HEIGHT = 20;
    private static final int FIRST_ROW_Y_OFFSET = 17;
    private static final int CHIP_X_OFFSET = 95;
    private static final int CHIP_WIDTH = 10;
    private static final int CHIP_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 7;

    public static void render(MerchantScreen screen, int leftPos, int topPos, int scrollOff,
                              GuiGraphicsExtractor g, int mouseX, int mouseY, Font font) {
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        if (!cfg.showMerchantOverlay) return;

        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers == null || offers.isEmpty()) return;

        VillagerEntry snapshot = ClientTradeState.snapshot().orElse(null);

        // Index our snapshot offers in the same order as vanilla for chip-matching.
        // (Server-side snapshot was captured from the same villager so order should align.)
        for (int slot = 0; slot < VISIBLE_ROWS; slot++) {
            int offerIndex = scrollOff + slot;
            if (offerIndex >= offers.size()) break;

            MerchantOffer offer = offers.get(offerIndex);
            int chipX = leftPos + CHIP_X_OFFSET;
            int chipY = topPos + FIRST_ROW_Y_OFFSET + slot * ROW_HEIGHT + 1;

            OfferEntry snapOffer = matchSnapshotOffer(snapshot, offer, offerIndex);
            TradeRating rating = snapOffer != null ? snapOffer.rating() : TradeRating.UNKNOWN;

            // Chip background
            g.fill(chipX, chipY, chipX + CHIP_WIDTH, chipY + CHIP_HEIGHT, 0xFF202020);
            // Chip color
            g.fill(chipX + 1, chipY + 1, chipX + CHIP_WIDTH - 1, chipY + CHIP_HEIGHT - 1, rating.color());

            // BEST marker on the chip if this is the best price seen at this villager
            int bestAtVillager = snapshot != null && snapOffer != null
                    ? snapshotBest(snapshot, snapOffer.signature())
                    : -1;
            if (bestAtVillager > 0 && snapOffer.emeraldCost() > 0 && snapOffer.emeraldCost() == bestAtVillager) {
                g.text(font, "*", chipX + 3, chipY + 5, 0xFF000000);
            }

            // Tooltip on hover
            if (cfg.showMerchantTooltips && isInRow(mouseX, mouseY, leftPos, topPos, slot)) {
                renderTooltip(g, font, mouseX, mouseY, offer, snapOffer, snapshot);
            }
        }

        renderCycleBanner(g, font, screen.width, topPos);
    }

    private static OfferEntry matchSnapshotOffer(VillagerEntry snapshot, MerchantOffer live, int index) {
        if (snapshot == null) return null;
        if (index < snapshot.offers().size()) {
            OfferEntry o = snapshot.offers().get(index);
            if (sameSell(o.sell(), live.getResult())) return o;
        }
        // Fallback: scan by sell-item identity
        TradeSignature sig = TradeSignature.of(live.getResult());
        for (OfferEntry o : snapshot.offers()) {
            if (o.signature().equals(sig)) return o;
        }
        return null;
    }

    private static boolean sameSell(ItemStack a, ItemStack b) {
        return a != null && b != null && a.is(b.getItem());
    }

    private static int snapshotBest(VillagerEntry snapshot, TradeSignature sig) {
        Integer v = snapshot.bestPriceFor(sig);
        return v == null ? -1 : v;
    }

    private static boolean isInRow(int mouseX, int mouseY, int leftPos, int topPos, int slot) {
        int rowY = topPos + FIRST_ROW_Y_OFFSET + slot * ROW_HEIGHT;
        return mouseX >= leftPos + 5 && mouseX < leftPos + 104
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    private static void renderTooltip(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY,
                                      MerchantOffer offer, OfferEntry snapOffer, VillagerEntry snapshot) {
        List<Component> lines = new ArrayList<>();
        TradeRating r = snapOffer != null ? snapOffer.rating() : TradeRating.UNKNOWN;
        lines.add(Component.literal("Rating: " + r.label()));

        int cost = costOf(offer);
        if (cost > 0) lines.add(Component.literal("This price: " + cost + " emeralds"));

        if (snapshot != null && snapOffer != null) {
            Integer best = snapshot.bestPriceFor(snapOffer.signature());
            if (best != null) {
                if (cost > 0 && cost == best) {
                    lines.add(Component.literal("BEST ever from this villager"));
                } else if (cost > 0) {
                    lines.add(Component.literal("Best seen here: " + best + " emeralds"));
                }
            }
        }

        BaselinePrices.Range br = baselineRangeFor(offer);
        if (br != null) {
            lines.add(Component.literal("Baseline range: " + br.min() + " - " + br.max()));
        }

        g.setTooltipForNextFrame(font, lines.stream()
                .map(Component::getVisualOrderText)
                .toList(), mouseX, mouseY);
    }

    private static int costOf(MerchantOffer offer) {
        ItemStack a = offer.getBaseCostA();
        ItemStack b = offer.getCostB();
        if (a.is(Items.EMERALD)) return a.getCount();
        if (b.is(Items.EMERALD)) return b.getCount();
        return 0;
    }

    private static BaselinePrices.Range baselineRangeFor(MerchantOffer offer) {
        ItemStack a = offer.getBaseCostA();
        ItemStack sell = offer.getResult();
        if (sell.is(Items.EMERALD)) return BaselinePrices.buyRange(a.getItem());
        if (a.is(Items.EMERALD))    return BaselinePrices.sellRange(sell.getItem());
        return null;
    }

    private static void renderCycleBanner(GuiGraphicsExtractor g, Font font, int screenWidth, int topPos) {
        CycleStatusS2C s = ClientTradeState.cycleStatus();
        CycleStatusS2C.State st = s.state();
        if (st == CycleStatusS2C.State.IDLE) return;

        String text = switch (st) {
            case ACTIVE -> "CYCLING (" + s.attempts() + ")  Target: " + s.target().displayName();
            case FOUND  -> "FOUND: " + s.target().displayName() + " @ " + s.lastCost() + "e"
                    + (s.bestCost() > 0 && s.lastCost() == s.bestCost() ? "  (BEST)" : "")
                    + "  [Y re-roll, Z stop]";
            case ENDED  -> s.message().isEmpty() ? "Cycle ended." : "Cycle: " + s.message();
            default -> "";
        };
        int color = switch (st) {
            case ACTIVE -> 0xFFFFFF55;
            case FOUND  -> 0xFF55FF55;
            case ENDED  -> 0xFFFF8888;
            default -> 0xFFFFFFFF;
        };
        int w = font.width(text) + 8;
        int x = (screenWidth - w) / 2;
        int y = Math.max(2, topPos - 14);
        g.fill(x, y, x + w, y + 12, 0xCC000000);
        g.text(font, text, x + 4, y + 2, color);
    }
}
