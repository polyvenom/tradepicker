package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.network.PickerSubmitC2S;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replaces the vanilla trade dance: the player sees every level-N trade for this villager
 * as cards, picks exactly the required number (vanilla = 2), and the server locks those
 * trades in at min cost.
 *
 * For book-trade-rich professions (librarian) the catalogue is long, so this screen
 * scrolls — mouse wheel moves the visible window over the card grid.
 */
public final class TradePickerScreen extends Screen {

    private static final int CARD_WIDTH = 130;
    private static final int CARD_HEIGHT = 24;
    private static final int CARD_GAP = 4;
    private static final int COLUMNS = 2;
    private static final int TOP_PAD = 50;
    private static final int BOTTOM_RESERVED = 60; // room for buttons + hover tooltip

    private final OpenPickerS2C data;
    private final Set<Integer> selectedIndices = new HashSet<>();
    private Button confirmBtn;
    private int scrollRow = 0;

    public TradePickerScreen(OpenPickerS2C data) {
        super(Component.literal("Trade Picker"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();

        confirmBtn = Button.builder(Component.literal("Confirm"), b -> onConfirm())
                .bounds(this.width / 2 - 84, this.height - 30, 80, 20)
                .build();
        confirmBtn.active = false;
        addRenderableWidget(confirmBtn);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(this.width / 2 + 4, this.height - 30, 80, 20)
                .build());
    }

    private void onConfirm() {
        if (selectedIndices.size() != data.picksRequired()) return;
        List<TradeKey> picks = new ArrayList<>();
        for (int idx : selectedIndices) picks.add(data.available().get(idx).key());
        ClientPlayNetworking.send(new PickerSubmitC2S(data.villagerId(), data.level(), picks));
        onClose();
    }

    private int visibleRows() {
        int available = this.height - TOP_PAD - BOTTOM_RESERVED;
        return Math.max(1, available / (CARD_HEIGHT + CARD_GAP));
    }

    private int totalRows() {
        int n = data.available().size();
        return (n + COLUMNS - 1) / COLUMNS;
    }

    private int maxScroll() {
        return Math.max(0, totalRows() - visibleRows());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        // Title row
        String levelName = levelName(data.level());
        String profDisplay = shortProf(data.profession());
        String title = String.format(Locale.ROOT, "%s — %s — pick %d trade(s)",
                profDisplay, levelName, data.picksRequired());
        g.text(this.font, title, (this.width - this.font.width(title)) / 2, 20, 0xFFFFFFFF);

        String status = String.format(Locale.ROOT, "%d / %d selected",
                selectedIndices.size(), data.picksRequired());
        g.text(this.font, status, (this.width - this.font.width(status)) / 2, 34, 0xFFAAAAAA);

        // Card grid
        int gridStartX = (this.width - (COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP)) / 2;
        int visible = visibleRows();
        List<AvailableTrade> trades = data.available();
        int firstIdx = scrollRow * COLUMNS;
        int lastIdx = Math.min(trades.size(), firstIdx + visible * COLUMNS);

        AvailableTrade hovered = null;
        for (int i = firstIdx; i < lastIdx; i++) {
            int slot = i - firstIdx;
            int col = slot % COLUMNS;
            int row = slot / COLUMNS;
            int cx = gridStartX + col * (CARD_WIDTH + CARD_GAP);
            int cy = TOP_PAD + row * (CARD_HEIGHT + CARD_GAP);
            if (drawCard(g, trades.get(i), cx, cy, i, mouseX, mouseY)) {
                hovered = trades.get(i);
            }
        }

        // Hover tooltip line (above the bottom buttons)
        if (hovered != null) {
            String label = describeOffer(hovered.previewOffer());
            int tx = (this.width - this.font.width(label)) / 2;
            int ty = this.height - 50;
            g.text(this.font, label, tx, ty, 0xFFFFCC55);
        }

        // Scroll affordance
        if (maxScroll() > 0) {
            String hint = "Scroll for more (" + (scrollRow + 1) + " / " + (maxScroll() + 1) + ")";
            g.text(this.font, hint, (this.width - this.font.width(hint)) / 2,
                    this.height - 50 - (hovered == null ? 0 : 12), 0xFF888888);
        }
    }

    private boolean drawCard(GuiGraphicsExtractor g, AvailableTrade trade, int x, int y, int idx, int mouseX, int mouseY) {
        boolean selected = selectedIndices.contains(idx);
        boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;

        int bg = selected ? 0xFF55AA55 : hovered ? 0xFF606060 : 0xFF404040;
        g.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, bg);
        int border = selected ? 0xFFAAFFAA : 0xFF808080;
        g.fill(x, y, x + CARD_WIDTH, y + 1, border);
        g.fill(x, y + CARD_HEIGHT - 1, x + CARD_WIDTH, y + CARD_HEIGHT, border);
        g.fill(x, y, x + 1, y + CARD_HEIGHT, border);
        g.fill(x + CARD_WIDTH - 1, y, x + CARD_WIDTH, y + CARD_HEIGHT, border);

        MerchantOffer offer = trade.previewOffer();
        ItemStack a = offer.getBaseCostA();
        ItemStack b = offer.getCostB();
        ItemStack r = offer.getResult();

        int ix = x + 4;
        int iy = y + 4;
        g.item(a, ix, iy);
        g.itemDecorations(this.font, a, ix, iy);

        int afterA = ix + 18;
        if (!b.isEmpty()) {
            g.item(b, afterA, iy);
            g.itemDecorations(this.font, b, afterA, iy);
            afterA += 18;
        }
        g.text(this.font, "→", afterA, iy + 4, 0xFFCCCCCC);
        afterA += 8;
        g.item(r, afterA, iy);
        g.itemDecorations(this.font, r, afterA, iy);

        return hovered;
    }

    private String describeOffer(MerchantOffer o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.getBaseCostA().getCount()).append("x ")
                .append(o.getBaseCostA().getHoverName().getString());
        if (!o.getCostB().isEmpty()) {
            sb.append(" + ").append(o.getCostB().getCount()).append("x ")
                    .append(o.getCostB().getHoverName().getString());
        }
        sb.append("  →  ").append(o.getResult().getCount()).append("x ")
                .append(o.getResult().getHoverName().getString());
        return sb.toString();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);

        int gridStartX = (this.width - (COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP)) / 2;
        int visible = visibleRows();
        List<AvailableTrade> trades = data.available();
        int firstIdx = scrollRow * COLUMNS;
        int lastIdx = Math.min(trades.size(), firstIdx + visible * COLUMNS);

        double mx = event.x(), my = event.y();
        for (int i = firstIdx; i < lastIdx; i++) {
            int slot = i - firstIdx;
            int col = slot % COLUMNS;
            int row = slot / COLUMNS;
            int cx = gridStartX + col * (CARD_WIDTH + CARD_GAP);
            int cy = TOP_PAD + row * (CARD_HEIGHT + CARD_GAP);
            if (mx >= cx && mx < cx + CARD_WIDTH && my >= cy && my < cy + CARD_HEIGHT) {
                toggleSelection(i);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && maxScroll() > 0) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void toggleSelection(int idx) {
        if (selectedIndices.contains(idx)) {
            selectedIndices.remove(idx);
        } else if (selectedIndices.size() < data.picksRequired()) {
            selectedIndices.add(idx);
        }
        confirmBtn.active = (selectedIndices.size() == data.picksRequired());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String levelName(int level) {
        return switch (level) {
            case 1 -> "Novice";
            case 2 -> "Apprentice";
            case 3 -> "Journeyman";
            case 4 -> "Expert";
            case 5 -> "Master";
            default -> "Level " + level;
        };
    }

    private static String shortProf(String prof) {
        int idx = prof.lastIndexOf(':');
        return (idx >= 0 ? prof.substring(idx + 1) : prof).toUpperCase(Locale.ROOT);
    }
}
