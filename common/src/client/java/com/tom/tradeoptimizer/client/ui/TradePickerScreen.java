package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.client.platform.ClientServices;
import com.tom.tradeoptimizer.network.PickerSubmitC2S;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    /** Cards this villager already sells (earlier-level picks / legacy) — marked, still pickable. */
    private final Set<Integer> ownedIndices = new HashSet<>();

    /** All indices into data.available() in display order: sorted by type (issue #7). */
    private final List<Integer> sortedOrder = new ArrayList<>();

    /** Indices into data.available() filtered by current search text, in display order. */
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
        ownedIndices.clear();
        Set<ResourceLocation> ownedIds = new HashSet<>();
        for (var key : data.ownedKeys()) ownedIds.add(key.id());
        for (int i = 0; i < data.available().size(); i++) {
            AvailableTrade trade = data.available().get(i);
            boolean owned = ownedIds.contains(trade.key().id());
            if (owned) ownedIndices.add(i);
            cardLabels.add(buildCardLabel(trade));
            cardTooltips.add(buildTooltip(trade) + (owned ? "  (already on this villager)" : ""));
        }
        rebuildSortOrder();
        rebuildFilter("");

        titleText = String.format(Locale.ROOT, "%s — %s — pick %d trade(s)",
                shortProf(data.profession()), levelName(data.level()), data.picksRequired());
        if (data.maxBookPicks() < data.picksRequired()) {
            titleText += String.format(Locale.ROOT, " (max %d book)", data.maxBookPicks());
        }
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

    /**
     * Default sort by type (issue #7): cards grouped by result item, then by their label's base
     * name (e.g. the enchantment), then by numeric level, then by pool order as the stable tie.
     * Only the DISPLAY order changes — selections and network picks still use pool indices.
     */
    private void rebuildSortOrder() {
        sortedOrder.clear();
        for (int i = 0; i < data.available().size(); i++) sortedOrder.add(i);
        List<String> itemIds = new ArrayList<>(data.available().size());
        List<String> baseNames = new ArrayList<>(data.available().size());
        List<Integer> levels = new ArrayList<>(data.available().size());
        for (int i = 0; i < data.available().size(); i++) {
            ItemStack result = data.available().get(i).previewOffer().getResult();
            itemIds.add(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            String label = cardLabels.get(i);
            // Gear labels can end in " +N" (bonus enchant count) — not part of the name.
            int plus = label.lastIndexOf(" +");
            if (plus > 0) label = label.substring(0, plus);
            // A trailing roman/arabic numeral is the level; the rest is the base name.
            int lastSpace = label.lastIndexOf(' ');
            int lvl = lastSpace > 0 ? parseLevel(label.substring(lastSpace + 1)) : 0;
            if (lvl > 0) label = label.substring(0, lastSpace);
            baseNames.add(label);
            levels.add(Math.max(lvl, 1));
        }
        sortedOrder.sort((a, b) -> {
            int c = itemIds.get(a).compareTo(itemIds.get(b));
            if (c != 0) return c;
            c = baseNames.get(a).compareTo(baseNames.get(b));
            if (c != 0) return c;
            c = Integer.compare(levels.get(a), levels.get(b));
            if (c != 0) return c;
            return Integer.compare(a, b);
        });
    }

    private static int parseLevel(String token) {
        switch (token) {
            case "I": return 1;
            case "II": return 2;
            case "III": return 3;
            case "IV": return 4;
            case "V": return 5;
            default:
                try {
                    return Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return 0;
                }
        }
    }

    private void rebuildFilter(String query) {
        filtered.clear();
        if (query == null || query.isBlank()) {
            filtered.addAll(sortedOrder);
            return;
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (int i : sortedOrder) {
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        g.drawString(this.font, titleText, (this.width - this.font.width(titleText)) / 2, 16, 0xFFFFFFFF);
        g.drawString(this.font, statusText, (this.width - this.font.width(statusText)) / 2, 30, 0xFFAAAAAA);

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
            g.drawString(this.font, label, tx, ty, 0xFFFFCC55);
        }

        if (filtered.isEmpty()) {
            String msg = "No trades match your search.";
            g.drawString(this.font, msg, (this.width - this.font.width(msg)) / 2, TOP_PAD + 12, 0xFFAAAAAA);
        } else if (maxScroll() > 0) {
            String hint = "Scroll for more (page " + (scrollRow + 1) + " / " + (maxScroll() + 1) + ")";
            int hintY = this.height - 50 - (hoveredIdx >= 0 ? 12 : 0);
            g.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, hintY, 0xFF888888);
        }
    }

    private boolean drawCard(GuiGraphics g, AvailableTrade trade, String enchLabel,
                             int x, int y, int idx, int mouseX, int mouseY) {
        boolean selected = selectedIndices.contains(idx);
        boolean blocked = isBookBlocked(idx);
        boolean owned = ownedIndices.contains(idx);
        boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;

        int bg = selected ? 0xFF55AA55 : blocked ? 0xFF2A2A2A : owned ? 0xFF303830
                : hovered ? 0xFF606060 : 0xFF404040;
        g.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, bg);
        int border = selected ? 0xFFAAFFAA : owned ? 0xFF60A060 : 0xFF808080;
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
        g.renderItem(a, ix, iy);
        g.renderItemDecorations(this.font, a, ix, iy);

        int afterA = ix + 18;
        if (!b.isEmpty()) {
            g.renderItem(b, afterA, iy);
            g.renderItemDecorations(this.font, b, afterA, iy);
            afterA += 18;
        }
        g.drawString(this.font, "→", afterA, iy + 4, 0xFFCCCCCC);
        afterA += 8;
        g.renderItem(r, afterA, iy);
        g.renderItemDecorations(this.font, r, afterA, iy);

        int rightPad = LABEL_RIGHT_PAD;
        if (owned) {
            // Check mark at the card's right edge: this trade is already on the villager.
            g.drawString(this.font, "✔", x + CARD_WIDTH - 12, y + 9, 0xFF55CC55);
            rightPad += 12;
        }

        if (!enchLabel.isEmpty()) {
            int labelX = afterA + 18;
            int maxWidth = (x + CARD_WIDTH - rightPad) - labelX;
            String fitted = fitText(enchLabel, maxWidth);
            g.drawString(this.font, fitted, labelX, y + 9,
                    blocked ? 0xFF707070 : owned ? 0xFFB0B090 : 0xFFFFFFAA);
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

    private static String buildCardLabel(AvailableTrade trade) {
        ItemStack result = trade.previewOffer().getResult();
        if (result.is(Items.ENCHANTED_BOOK)) {
            return storedEnchantDisplay(result);
        }
        if (result.is(Items.TIPPED_ARROW)) {
            return potionDisplay(result);
        }
        ItemEnchantments enchants = result.getEnchantments();
        if (!enchants.isEmpty()) {
            return gearEnchantLabel(trade.key(), enchants);
        }
        return "";
    }

    private static String buildTooltip(AvailableTrade trade) {
        MerchantOffer o = trade.previewOffer();
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
            String ench = storedEnchantDisplay(r);
            sb.append(ench.isEmpty() ? "Enchanted Book" : ench + " Book");
        } else if (r.is(Items.TIPPED_ARROW)) {
            sb.append(r.getHoverName().getString());
        } else if (!r.getEnchantments().isEmpty()) {
            sb.append(r.getHoverName().getString()).append(" — ").append(allEnchantsDisplay(r.getEnchantments()));
            if (OfferFactory.headlineEnchantId(trade.key()).isPresent()) {
                sb.append("  (you choose the headline; level + bonuses rolled by the game)");
            }
        } else {
            sb.append(r.getHoverName().getString());
        }
        return sb.toString();
    }

    /** Book label: the single stored enchantment. */
    private static String storedEnchantDisplay(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return "";
        Holder<Enchantment> ench = enchants.keySet().iterator().next();
        return enchantName(ench, enchants.getLevel(ench));
    }

    /** Gear card label: lead with the chosen (headline) enchantment, note how many bonuses ride along. */
    private static String gearEnchantLabel(TradeKey key, ItemEnchantments enchants) {
        Holder<Enchantment> lead = null;
        Optional<ResourceLocation> headline = OfferFactory.headlineEnchantId(key);
        if (headline.isPresent()) {
            for (Holder<Enchantment> h : enchants.keySet()) {
                if (h.unwrapKey().map(k -> k.location().equals(headline.get())).orElse(false)) {
                    lead = h;
                    break;
                }
            }
        }
        if (lead == null) lead = enchants.keySet().iterator().next();
        String s = enchantName(lead, enchants.getLevel(lead));
        int more = enchants.size() - 1;
        if (more > 0) s += " +" + more;
        return s;
    }

    private static String allEnchantsDisplay(ItemEnchantments enchants) {
        List<String> parts = new ArrayList<>();
        for (Holder<Enchantment> h : enchants.keySet()) {
            parts.add(enchantName(h, enchants.getLevel(h)));
        }
        parts.sort(String::compareTo);
        return String.join(", ", parts);
    }

    private static String potionDisplay(ItemStack stack) {
        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return "";
        return pc.potion()
                .flatMap(Holder::unwrapKey)
                .map(k -> capitalize(k.location().getPath().replace('_', ' ')))
                .orElse("");
    }

    private static String enchantName(Holder<Enchantment> ench, int level) {
        String pathStr = ench.unwrapKey().map(k -> k.location().getPath()).orElse("enchant");
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int gridStartX = (this.width - (COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP)) / 2;
        int visible = visibleRows();
        int firstSlot = scrollRow * COLUMNS;
        int lastSlot = Math.min(filtered.size(), firstSlot + visible * COLUMNS);

        double mx = mouseX, my = mouseY;
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
        return super.mouseClicked(mouseX, mouseY, button);
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
            // Vanilla book-limit mode: don't let books past the per-level cap. When the toggle is
            // off, maxBookPicks == picksRequired so this never blocks anything.
            if (isBookCard(idx) && selectedBookCount() >= data.maxBookPicks()) {
                return;
            }
            selectedIndices.add(idx);
        }
        confirmBtn.active = (selectedIndices.size() == data.picksRequired());
        rebuildStatusText();
    }

    private boolean isBookCard(int idx) {
        return data.available().get(idx).previewOffer().getResult().is(Items.ENCHANTED_BOOK);
    }

    private int selectedBookCount() {
        int c = 0;
        for (int i : selectedIndices) {
            if (isBookCard(i)) c++;
        }
        return c;
    }

    /** A book card the player can't currently pick because the per-level book cap is reached. */
    private boolean isBookBlocked(int idx) {
        return isBookCard(idx)
                && !selectedIndices.contains(idx)
                && selectedBookCount() >= data.maxBookPicks();
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
