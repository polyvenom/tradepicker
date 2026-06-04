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
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
 * Performance notes (audit, 2026-06):
 *  - Card labels are computed once in init() and cached in a parallel List<String>.
 *    Previously we were calling getHoverName() + StringBuilder per frame per visible card.
 *  - Title and status strings are cached. Status is rebuilt only when selection changes.
 *  - extractRenderState now allocates effectively nothing per frame (just text rendering).
 *
 * Visual:
 *  - For enchanted-book results we extract the stored enchantment + level and print it
 *    on the right side of the card so each book card is visually distinct.
 */
public final class TradePickerScreen extends Screen {

    private static final int CARD_WIDTH = 180;
    private static final int CARD_HEIGHT = 24;
    private static final int CARD_GAP = 4;
    private static final int COLUMNS = 2;
    private static final int TOP_PAD = 50;
    private static final int BOTTOM_RESERVED = 60;
    /** Right-edge padding inside the card so the enchantment label doesn't overflow. */
    private static final int LABEL_RIGHT_PAD = 6;

    private final OpenPickerS2C data;
    private final Set<Integer> selectedIndices = new HashSet<>();

    /** Pre-computed display data per trade — populated in init(), used per frame. */
    private final List<String> cardLabels = new ArrayList<>();
    private final List<String> cardTooltips = new ArrayList<>();

    private Button confirmBtn;
    private int scrollRow = 0;

    // Cached strings (avoid per-frame String.format allocations)
    private String titleText = "";
    private String statusText = "";

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

        // Pre-compute the display strings once. These never change for a given picker
        // session, so there's no reason to rebuild them every frame.
        cardLabels.clear();
        cardTooltips.clear();
        for (AvailableTrade trade : data.available()) {
            cardLabels.add(buildCardLabel(trade.previewOffer()));
            cardTooltips.add(buildTooltip(trade.previewOffer()));
        }

        titleText = String.format(Locale.ROOT, "%s — %s — pick %d trade(s)",
                shortProf(data.profession()), levelName(data.level()), data.picksRequired());
        rebuildStatusText();
    }

    private void rebuildStatusText() {
        statusText = String.format(Locale.ROOT, "%d / %d selected",
                selectedIndices.size(), data.picksRequired());
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

        g.text(this.font, titleText, (this.width - this.font.width(titleText)) / 2, 20, 0xFFFFFFFF);
        g.text(this.font, statusText, (this.width - this.font.width(statusText)) / 2, 34, 0xFFAAAAAA);

        int gridStartX = (this.width - (COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP)) / 2;
        int visible = visibleRows();
        List<AvailableTrade> trades = data.available();
        int firstIdx = scrollRow * COLUMNS;
        int lastIdx = Math.min(trades.size(), firstIdx + visible * COLUMNS);

        int hoveredIdx = -1;
        for (int i = firstIdx; i < lastIdx; i++) {
            int slot = i - firstIdx;
            int col = slot % COLUMNS;
            int row = slot / COLUMNS;
            int cx = gridStartX + col * (CARD_WIDTH + CARD_GAP);
            int cy = TOP_PAD + row * (CARD_HEIGHT + CARD_GAP);
            if (drawCard(g, trades.get(i), cardLabels.get(i), cx, cy, i, mouseX, mouseY)) {
                hoveredIdx = i;
            }
        }

        if (hoveredIdx >= 0) {
            String label = cardTooltips.get(hoveredIdx);
            int tx = (this.width - this.font.width(label)) / 2;
            int ty = this.height - 50;
            g.text(this.font, label, tx, ty, 0xFFFFCC55);
        }

        if (maxScroll() > 0) {
            String hint = "Scroll for more (page " + (scrollRow + 1) + " / " + (maxScroll() + 1) + ")";
            int hintY = this.height - 50 - (hoveredIdx >= 0 ? 12 : 0);
            g.text(this.font, hint, (this.width - this.font.width(hint)) / 2, hintY, 0xFF888888);
        }
    }

    private boolean drawCard(GuiGraphicsExtractor g, AvailableTrade trade, String enchLabel,
                             int x, int y, int idx, int mouseX, int mouseY) {
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

        // Enchantment label on the right side of the card — this is what tells the
        // player which book they're picking. Auto-truncate if it doesn't fit.
        if (!enchLabel.isEmpty()) {
            int labelX = afterA + 18;
            int maxWidth = (x + CARD_WIDTH - LABEL_RIGHT_PAD) - labelX;
            String fitted = fitText(enchLabel, maxWidth);
            g.text(this.font, fitted, labelX, y + 9, 0xFFFFFFAA);
        }

        return hovered;
    }

    /** Shorten with a trailing dot if it would overflow the available pixel width. */
    private String fitText(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        while (text.length() > 1 && this.font.width(text + ".") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ".";
    }

    /** Card right-side label — only populated for enchanted books. */
    private static String buildCardLabel(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        if (!result.is(Items.ENCHANTED_BOOK)) return "";
        return enchantmentDisplay(result);
    }

    /** Full hover-line description. */
    private static String buildTooltip(MerchantOffer o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.getBaseCostA().getCount()).append("x ")
                .append(o.getBaseCostA().getHoverName().getString());
        if (!o.getCostB().isEmpty()) {
            sb.append(" + ").append(o.getCostB().getCount()).append("x ")
                    .append(o.getCostB().getHoverName().getString());
        }
        sb.append("  →  ").append(o.getResult().getCount()).append("x ");

        ItemStack r = o.getResult();
        if (r.is(Items.ENCHANTED_BOOK)) {
            String ench = enchantmentDisplay(r);
            sb.append(ench.isEmpty() ? "Enchanted Book" : ench + " Book");
        } else {
            sb.append(r.getHoverName().getString());
        }
        return sb.toString();
    }

    /** Pull the first stored enchantment off an item and format as "Name [Roman]". */
    private static String enchantmentDisplay(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return "";
        Holder<Enchantment> ench = enchants.keySet().iterator().next();
        int level = enchants.getLevel(ench);
        String pathStr = ench.unwrapKey().map(k -> k.identifier().getPath()).orElse("enchant");
        String base = capitalize(pathStr.replace('_', ' '));
        return level > 1 ? base + " " + roman(level) : base;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (cap && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
            if (c == ' ') cap = true;
        }
        return sb.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            default -> String.valueOf(n);
        };
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
        rebuildStatusText();
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
