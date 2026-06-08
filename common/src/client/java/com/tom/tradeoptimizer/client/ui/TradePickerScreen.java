package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.client.platform.ClientServices;
import com.tom.tradeoptimizer.network.PickerSubmitC2S;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
 * Picker UI: lists every level-N trade for a villager as cards, lets the player pick
 * the required number (vanilla = 2), and ships the picks back to the server.
 *
 * Search: an EditBox above the grid filters cards in real time. Match is case-insensitive
 * and runs against the same tooltip text shown on hover, so typing "mending" surfaces just
 * the Mending Book card, "sharp" surfaces all five Sharpness levels, "emerald" surfaces
 * every emerald-buy trade, etc.
 */
public final class TradePickerScreen extends Screen {

    private static final int CARD_WIDTH = 180;
    private static final int CARD_HEIGHT = 24;
    private static final int CARD_GAP = 4;
    private static final int COLUMNS = 2;
    private static final int TOP_PAD = 70; // raised to make room for the search box
    private static final int BOTTOM_RESERVED = 60;
    private static final int LABEL_RIGHT_PAD = 6;

    private final OpenPickerS2C data;
    private final Set<Integer> selectedIndices = new HashSet<>();

    /** Pre-computed display data per trade — never changes for this session. */
    private final List<String> cardLabels = new ArrayList<>();
    private final List<String> cardTooltips = new ArrayList<>();

    /** Indices into data.available() filtered by current search text. */
    private final List<Integer> filtered = new ArrayList<>();

    private EditBox searchBox;
    private Button confirmBtn;
    private int scrollRow = 0;

    private String titleText = "";
    private String statusText = "";

    public TradePickerScreen(OpenPickerS2C data) {
        super(Component.literal("Trade Picker"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();

        // Pre-compute display data once.
        cardLabels.clear();
        cardTooltips.clear();
        for (AvailableTrade trade : data.available()) {
            cardLabels.add(buildCardLabel(trade.previewOffer()));
            cardTooltips.add(buildTooltip(trade.previewOffer()));
        }
        rebuildFilter("");

        titleText = String.format(Locale.ROOT, "%s — %s — pick %d trade(s)",
                shortProf(data.profession()), levelName(data.level()), data.picksRequired());
        rebuildStatusText();

        // Search box (centered horizontally)
        int boxW = COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP;
        int boxX = (this.width - boxW) / 2;
        searchBox = new EditBox(this.font, boxX, 44, boxW, 18, Component.literal("Search"));
        searchBox.setMaxLength(48);
        searchBox.setHint(Component.literal("Search trades (e.g. 'mending', 'sharp', 'emerald')"));
        searchBox.setResponder(text -> {
            scrollRow = 0;
            rebuildFilter(text);
        });
        addRenderableWidget(searchBox);

        confirmBtn = Button.builder(Component.literal("Confirm"), b -> onConfirm())
                .bounds(this.width / 2 - 84, this.height - 30, 80, 20)
                .build();
        confirmBtn.active = false;
        addRenderableWidget(confirmBtn);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(this.width / 2 + 4, this.height - 30, 80, 20)
                .build());
    }

    private void rebuildFilter(String query) {
        filtered.clear();
        if (query == null || query.isBlank()) {
            for (int i = 0; i < data.available().size(); i++) filtered.add(i);
            return;
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (int i = 0; i < cardTooltips.size(); i++) {
            if (cardTooltips.get(i).toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(i);
            }
        }
    }

    private void rebuildStatusText() {
        statusText = String.format(Locale.ROOT, "%d / %d selected",
                selectedIndices.size(), data.picksRequired());
    }

    private void onConfirm() {
        if (selectedIndices.size() != data.picksRequired()) return;
        List<TradeKey> picks = new ArrayList<>();
        for (int idx : selectedIndices) picks.add(data.available().get(idx).key());
        ClientServices.NETWORK.sendToServer(new PickerSubmitC2S(data.villagerId(), data.level(), picks));
        onClose();
    }

    private int visibleRows() {
        int available = this.height - TOP_PAD - BOTTOM_RESERVED;
        return Math.max(1, available / (CARD_HEIGHT + CARD_GAP));
    }

    private int totalRows() {
        int n = filtered.size();
        return (n + COLUMNS - 1) / COLUMNS;
    }

    private int maxScroll() {
        return Math.max(0, totalRows() - visibleRows());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        g.text(this.font, titleText, (this.width - this.font.width(titleText)) / 2, 16, 0xFFFFFFFF);
        g.text(this.font, statusText, (this.width - this.font.width(statusText)) / 2, 30, 0xFFAAAAAA);

        int gridStartX = (this.width - (COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP)) / 2;
        int visible = visibleRows();
        int firstSlot = scrollRow * COLUMNS;
        int lastSlot = Math.min(filtered.size(), firstSlot + visible * COLUMNS);

        int hoveredIdx = -1;
        for (int slotPos = firstSlot; slotPos < lastSlot; slotPos++) {
            int dataIdx = filtered.get(slotPos);
            int posInPage = slotPos - firstSlot;
            int col = posInPage % COLUMNS;
            int row = posInPage / COLUMNS;
            int cx = gridStartX + col * (CARD_WIDTH + CARD_GAP);
            int cy = TOP_PAD + row * (CARD_HEIGHT + CARD_GAP);
            if (drawCard(g, data.available().get(dataIdx), cardLabels.get(dataIdx), cx, cy, dataIdx, mouseX, mouseY)) {
                hoveredIdx = dataIdx;
            }
        }

        if (hoveredIdx >= 0) {
            String label = cardTooltips.get(hoveredIdx);
            int tx = (this.width - this.font.width(label)) / 2;
            int ty = this.height - 50;
            g.text(this.font, label, tx, ty, 0xFFFFCC55);
        }

        if (filtered.isEmpty()) {
            String msg = "No trades match your search.";
            g.text(this.font, msg, (this.width - this.font.width(msg)) / 2, TOP_PAD + 12, 0xFFAAAAAA);
        } else if (maxScroll() > 0) {
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

        if (!enchLabel.isEmpty()) {
            int labelX = afterA + 18;
            int maxWidth = (x + CARD_WIDTH - LABEL_RIGHT_PAD) - labelX;
            String fitted = fitText(enchLabel, maxWidth);
            g.text(this.font, fitted, labelX, y + 9, 0xFFFFFFAA);
        }

        return hovered;
    }

    private String fitText(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        while (text.length() > 1 && this.font.width(text + ".") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ".";
    }

    private static String buildCardLabel(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        if (!result.is(Items.ENCHANTED_BOOK)) return "";
        return enchantmentDisplay(result);
    }

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

    private static String enchantmentDisplay(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return "";
        Holder<Enchantment> ench = enchants.keySet().iterator().next();
        int level = enchants.getLevel(ench);
        String pathStr = ench.unwrapKey().map(k -> k.identifier().getPath()).orElse("enchant");
        String base = capitalize(pathStr.replace('_', ' '));
        // Match vanilla: single-level enchantments (Mending, Silk Touch, Infinity, ...)
        // display with no numeral. Multi-level enchantments always show their numeral.
        if (level == 1 && ench.value().getMaxLevel() == 1) return base;
        return base + " " + roman(level);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (cap && Character.isLetter(c)) { sb.append(Character.toUpperCase(c)); cap = false; }
            else { sb.append(c); }
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
        int firstSlot = scrollRow * COLUMNS;
        int lastSlot = Math.min(filtered.size(), firstSlot + visible * COLUMNS);

        double mx = event.x(), my = event.y();
        for (int slotPos = firstSlot; slotPos < lastSlot; slotPos++) {
            int dataIdx = filtered.get(slotPos);
            int posInPage = slotPos - firstSlot;
            int col = posInPage % COLUMNS;
            int row = posInPage / COLUMNS;
            int cx = gridStartX + col * (CARD_WIDTH + CARD_GAP);
            int cy = TOP_PAD + row * (CARD_HEIGHT + CARD_GAP);
            if (mx >= cx && mx < cx + CARD_WIDTH && my >= cy && my < cy + CARD_HEIGHT) {
                toggleSelection(dataIdx);
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
