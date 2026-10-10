package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.mojang.blaze3d.platform.InputConstants;
import com.tom.tradeoptimizer.client.platform.ClientServices;
import com.tom.tradeoptimizer.network.PickerSubmitC2S;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picker UI: lists every level-N trade for a villager as cards, lets the player pick
 * the required number (vanilla = 2), and ships the picks back to the server.
 *
 * The search box filters cards case-insensitively against the tooltip text shown on hover.
 */
public final class TradePickerScreen extends Screen {

    private static final int CARD_WIDTH = 180;
    private static final int CARD_HEIGHT = 24;
    private static final int CARD_GAP = 4;
    private static final int COLUMNS = 2;
    private static final int TOP_PAD = 70;
    private static final int BOTTOM_RESERVED = 60;
    private static final int LABEL_RIGHT_PAD = 6;
    /** 3s — a submit that hasn't been answered by then was refused server-side. */
    private static final int SUBMIT_TIMEOUT_TICKS = 60;

    private final OpenPickerS2C data;
    // Insertion-ordered: the villager lists the trades in the order they were clicked (#23).
    private final Set<Integer> selectedIndices = new LinkedHashSet<>();

    /** Pre-computed display data per trade — never changes for this session. */
    private final List<String> cardLabels = new ArrayList<>();
    private final List<String> cardTooltips = new ArrayList<>();
    /** What the search box matches: the trade itself, never the annotations around it (#18). */
    private final List<String> cardSearch = new ArrayList<>();

    /** Cards this villager already sells (earlier-level picks / legacy) — marked, still pickable. */
    private final Set<Integer> ownedIndices = new HashSet<>();

    /** All indices into data.available() in display order (see rebuildSortOrder). */
    private final List<Integer> sortedOrder = new ArrayList<>();

    /** Indices into data.available() filtered by current search text, in display order. */
    private final List<Integer> filtered = new ArrayList<>();

    private EditBox searchBox;
    private Button confirmBtn;
    private Button cancelBtn;
    private int scrollRow = 0;
    /** Survives init(): a resize or GUI-scale change rebuilds every widget (#18). */
    private String searchQuery = "";

    /**
     * Set once Confirm is pressed. The screen stays up while the server works, and whatever the
     * server opens next replaces it, so there is no blink back to the world.
     */
    private boolean submitted = false;

    /** Ticks spent waiting on the server, so a rejected submit can't strand the player. */
    private int submittedTicks = 0;

    private String titleText = "";
    private String statusText = "";
    private String noMatchText = "";

    /** Per-frame allocation cuts (#20): fitted labels by maxWidth then text, and the scroll hint by the ints it shows. */
    private final Map<Integer, Map<String, String>> fitCache = new HashMap<>();
    private String hintText = "";
    private int hintRow = -1;
    private int hintMax = -1;

    public TradePickerScreen(OpenPickerS2C data) {
        super(Component.translatable("tradeoptimizer.picker.title"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();

        fitCache.clear(); // widths change with GUI scale; also bounds growth
        cardLabels.clear();
        cardTooltips.clear();
        cardSearch.clear();
        ownedIndices.clear();
        Set<Identifier> ownedIds = new HashSet<>();
        for (var key : data.ownedKeys()) ownedIds.add(key.id());
        for (int i = 0; i < data.available().size(); i++) {
            AvailableTrade trade = data.available().get(i);
            boolean owned = ownedIds.contains(trade.key().id());
            if (owned) ownedIndices.add(i);
            cardLabels.add(buildCardLabel(trade));
            String text = buildTooltip(trade);
            cardSearch.add(text.toLowerCase(Locale.ROOT));
            String note = OfferFactory.headlineEnchantId(trade.key()).isPresent()
                    ? tr("tradeoptimizer.picker.headline_note") : "";
            cardTooltips.add(text + note + (owned ? tr("tradeoptimizer.picker.owned") : ""));
        }
        rebuildSortOrder();
        rebuildFilter(searchQuery);
        scrollRow = Math.min(scrollRow, maxScroll()); // a bigger window can show more rows: don't strand the view

        titleText = tr("tradeoptimizer.picker.heading",
                shortProf(data.profession()), levelName(data.level()), data.picksRequired());
        if (data.maxBookPicks() < data.picksRequired()) {
            titleText += tr("tradeoptimizer.picker.max_books", data.maxBookPicks());
        }
        noMatchText = tr("tradeoptimizer.picker.no_match");
        rebuildStatusText();

        int boxW = COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP;
        int boxX = (this.width - boxW) / 2;
        searchBox = new EditBox(this.font, boxX, 44, boxW, 18, Component.translatable("tradeoptimizer.picker.search"));
        searchBox.setMaxLength(48);
        searchBox.setHint(Component.translatable("tradeoptimizer.picker.search_hint"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(text -> {
            searchQuery = text;
            scrollRow = 0;
            rebuildFilter(text);
        });
        addRenderableWidget(searchBox);

        confirmBtn = Button.builder(Component.translatable("tradeoptimizer.picker.confirm"), b -> onConfirm())
                .bounds(this.width / 2 - 84, this.height - 30, 80, 20)
                .build();
        addRenderableWidget(confirmBtn);

        cancelBtn = Button.builder(Component.translatable("tradeoptimizer.picker.cancel"), b -> onClose())
                .bounds(this.width / 2 + 4, this.height - 30, 80, 20)
                .build();
        addRenderableWidget(cancelBtn);

        // init() reruns on resize / GUI-scale change and the selection survives it, so the buttons
        // must be re-synced to it (#18).
        confirmBtn.active = !submitted && selectedIndices.size() == data.picksRequired();
        cancelBtn.active = !submitted;
        if (submitted) statusText = tr("tradeoptimizer.picker.applying");
    }

    /**
     * Display order (#7): plain trades first, then enchanted cards (books + gear); within each group
     * by result item, lead enchantment / potion id, numeric level, then pool order. Keys come from
     * the data, never the localized labels. Selections and network picks still use pool indices.
     */
    private void rebuildSortOrder() {
        sortedOrder.clear();
        for (int i = 0; i < data.available().size(); i++) sortedOrder.add(i);
        List<Boolean> enchantedGroup = new ArrayList<>(data.available().size());
        List<String> itemIds = new ArrayList<>(data.available().size());
        List<String> baseKeys = new ArrayList<>(data.available().size());
        List<Integer> levels = new ArrayList<>(data.available().size());
        for (int i = 0; i < data.available().size(); i++) {
            AvailableTrade trade = data.available().get(i);
            ItemStack result = trade.previewOffer().getResult();
            enchantedGroup.add(result.is(Items.ENCHANTED_BOOK) || !result.getEnchantments().isEmpty());
            itemIds.add(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            String baseKey = "";
            int lvl = 0;
            if (result.is(Items.ENCHANTED_BOOK)) {
                ItemEnchantments stored = result.get(DataComponents.STORED_ENCHANTMENTS);
                if (stored != null && !stored.isEmpty()) {
                    Holder<Enchantment> h = stored.keySet().iterator().next();
                    baseKey = holderId(h);
                    lvl = stored.getLevel(h);
                }
            } else if (result.is(Items.TIPPED_ARROW)) {
                PotionContents pc = result.get(DataComponents.POTION_CONTENTS);
                if (pc != null) baseKey = pc.potion().map(TradePickerScreen::holderId).orElse("");
            } else if (result.has(DataComponents.SUSPICIOUS_STEW_EFFECTS) || result.has(DataComponents.DYED_COLOR)) {
                baseKey = trade.key().id().toString(); // effect / colour live in the key
            } else if (!result.getEnchantments().isEmpty()) {
                ItemEnchantments enchants = result.getEnchantments();
                Holder<Enchantment> lead = gearLead(trade.key(), enchants);
                baseKey = holderId(lead);
                lvl = enchants.getLevel(lead);
            }
            baseKeys.add(baseKey);
            levels.add(lvl);
        }
        sortedOrder.sort((a, b) -> {
            int c = Boolean.compare(enchantedGroup.get(a), enchantedGroup.get(b));
            if (c != 0) return c;
            c = itemIds.get(a).compareTo(itemIds.get(b));
            if (c != 0) return c;
            c = baseKeys.get(a).compareTo(baseKeys.get(b));
            if (c != 0) return c;
            c = Integer.compare(levels.get(a), levels.get(b));
            if (c != 0) return c;
            return Integer.compare(a, b);
        });
    }

    private static <T> String holderId(Holder<T> h) {
        return h.unwrapKey().map(k -> k.identifier().toString()).orElse("");
    }

    private void rebuildFilter(String query) {
        filtered.clear();
        if (query == null || query.isBlank()) {
            filtered.addAll(sortedOrder);
            return;
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (int i : sortedOrder) {
            if (cardSearch.get(i).contains(q)) {
                filtered.add(i);
            }
        }
    }

    private void rebuildStatusText() {
        statusText = tr("tradeoptimizer.picker.selected", selectedIndices.size(), data.picksRequired());
    }

    private void onConfirm() {
        if (submitted || selectedIndices.size() != data.picksRequired()) return;
        List<TradeKey> picks = new ArrayList<>();
        for (int idx : selectedIndices) picks.add(data.available().get(idx).key());
        ClientServices.NETWORK.sendToServer(new PickerSubmitC2S(data.villagerId(), data.level(), picks));

        // Hold the screen: the server's answer (merchant or next picker) replaces it directly.
        submitted = true;
        submittedTicks = 0;
        confirmBtn.active = false;
        cancelBtn.active = false;
        statusText = tr("tradeoptimizer.picker.applying");
    }

    @Override
    public void tick() {
        super.tick();
        if (!submitted) return;
        // The server can decline a submit (e.g. the book cap) and open nothing; hand the screen back.
        if (++submittedTicks > SUBMIT_TIMEOUT_TICKS) {
            submitted = false;
            confirmBtn.active = selectedIndices.size() == data.picksRequired();
            cancelBtn.active = true;
            rebuildStatusText();
        }
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
            String msg = noMatchText;
            g.text(this.font, msg, (this.width - this.font.width(msg)) / 2, TOP_PAD + 12, 0xFFAAAAAA);
        } else if (maxScroll() > 0) {
            int maxScroll = maxScroll();
            if (scrollRow != hintRow || maxScroll != hintMax) {
                hintRow = scrollRow;
                hintMax = maxScroll;
                hintText = tr("tradeoptimizer.picker.scroll_hint", scrollRow + 1, maxScroll + 1);
            }
            String hint = hintText;
            int hintY = this.height - 50 - (hoveredIdx >= 0 ? 12 : 0);
            g.text(this.font, hint, (this.width - this.font.width(hint)) / 2, hintY, 0xFF888888);
        }
    }

    private boolean drawCard(GuiGraphicsExtractor g, AvailableTrade trade, String enchLabel,
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

        int rightPad = LABEL_RIGHT_PAD;
        if (selected) {
            // Its place in the villager's list: trades are listed in click order (#23).
            String n = String.valueOf(selectionNumber(idx));
            g.text(this.font, n, x + CARD_WIDTH - 3 - this.font.width(n), y + 2, 0xFFFFFFFF);
            rightPad += 8;
        }
        if (owned) {
            g.text(this.font, "✔", x + CARD_WIDTH - 12, y + 9, 0xFF55CC55);
            rightPad += 12;
        }

        if (!enchLabel.isEmpty()) {
            int labelX = afterA + 18;
            int maxWidth = (x + CARD_WIDTH - rightPad) - labelX;
            String fitted = fitText(enchLabel, maxWidth);
            g.text(this.font, fitted, labelX, y + 9,
                    blocked ? 0xFF707070 : owned ? 0xFFB0B090 : 0xFFFFFFAA);
        }

        return hovered;
    }

    private String fitText(String text, int maxWidth) {
        return fitCache.computeIfAbsent(maxWidth, w -> new HashMap<>())
                .computeIfAbsent(text, t -> fitTextUncached(t, maxWidth));
    }

    private String fitTextUncached(String text, int maxWidth) {
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
        return variantDisplay(trade.key(), result);
    }

    /**
     * Stew and dyed-leather cards (#14): one card per effect or colour, so the card says which.
     * The colour comes from the card's key, not the item: a mixed dye has no single name.
     */
    private static String variantDisplay(TradeKey key, ItemStack stack) {
        SuspiciousStewEffects stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
        if (stew != null && stew.effects().size() == 1) {
            return stew.effects().get(0).effect().value().getDisplayName().getString();
        }
        String path = key.id().getPath();
        if (key.id().getNamespace().equals(TradeOptimizer.MOD_ID) && path.startsWith("dye/")) {
            String[] parts = path.split("/");
            if (parts.length > 1) return tr("color.minecraft." + parts[1]);
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
            sb.append(ench.isEmpty() ? tr("tradeoptimizer.picker.book") : tr("tradeoptimizer.picker.book_of", ench));
        } else if (r.is(Items.TIPPED_ARROW)) {
            // Vanilla bakes the potion into the hover name ("Arrow of Weakness"), already searchable.
            sb.append(r.getHoverName().getString());
        } else if (!r.getEnchantments().isEmpty()) {
            sb.append(r.getHoverName().getString()).append(" — ").append(allEnchantsDisplay(r.getEnchantments()));
        } else {
            sb.append(r.getHoverName().getString());
            String variant = variantDisplay(trade.key(), r);
            if (!variant.isEmpty()) sb.append(" — ").append(variant); // searchable by effect / colour
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

    /**
     * The gear card's lead enchantment: the player's chosen headline (parsed from the key in
     * HEADLINE mode), else the first one (the only enchantment in SINGLE mode).
     */
    private static Holder<Enchantment> gearLead(TradeKey key, ItemEnchantments enchants) {
        Optional<Identifier> headline = OfferFactory.headlineEnchantId(key);
        if (headline.isPresent()) {
            for (Holder<Enchantment> h : enchants.keySet()) {
                if (h.unwrapKey().map(k -> k.identifier().equals(headline.get())).orElse(false)) {
                    return h;
                }
            }
        }
        return enchants.keySet().iterator().next();
    }

    /**
     * Gear card label: lead with the lead enchantment, and note how many bonus enchantments ride
     * along so two headline cards with different bonuses still read distinctly.
     */
    private static String gearEnchantLabel(TradeKey key, ItemEnchantments enchants) {
        Holder<Enchantment> lead = gearLead(key, enchants);
        String s = enchantName(lead, enchants.getLevel(lead));
        int more = enchants.size() - 1;
        if (more > 0) s += " +" + more;
        return s;
    }

    /** Every enchantment on the item, for the tooltip (so search matches any of them). */
    private static String allEnchantsDisplay(ItemEnchantments enchants) {
        List<String> parts = new ArrayList<>();
        for (Holder<Enchantment> h : enchants.keySet()) {
            parts.add(enchantName(h, enchants.getLevel(h)));
        }
        parts.sort(String::compareTo);
        return String.join(", ", parts);
    }

    /** Tipped-arrow label: the potion's effect name ("Weakness"), or vanilla's arrow name for multi-effect potions. */
    private static String potionDisplay(ItemStack stack) {
        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return "";
        if (pc.potion().isPresent()) {
            List<MobEffectInstance> effects = pc.potion().get().value().getEffects();
            if (effects.size() == 1) {
                // Long and Strong variants share the effect's name, so the potency and the arrow's own
                // duration (an eighth of the potion's) keep their cards apart.
                MobEffectInstance e = effects.get(0);
                String name = e.getEffect().value().getDisplayName().getString();
                if (e.getAmplifier() > 0) name += " " + tr("potion.potency." + e.getAmplifier());
                if (e.getDuration() > 1) {
                    int secs = e.getDuration() / 8 / 20;
                    name += String.format(Locale.ROOT, " (%d:%02d)", secs / 60, secs % 60);
                }
                return name;
            }
        }
        return stack.getHoverName().getString();
    }

    /** Vanilla's localized name: numeral omitted for single-level enchantments, modded ones just work. */
    private static String enchantName(Holder<Enchantment> ench, int level) {
        return Enchantment.getFullname(ench, level).getString();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 26.3 renumbered the mouse buttons: left is MOUSE_BUTTON_LEFT (1), not GLFW's 0.
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        if (submitted) return super.mouseClicked(event, doubleClick);

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
        if (scrollY != 0) {
            // Always clamp, even when everything fits: a row left over from a smaller window must
            // be scrollable back (#18).
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void toggleSelection(int idx) {
        if (selectedIndices.contains(idx)) {
            selectedIndices.remove(idx);
        } else if (selectedIndices.size() < data.picksRequired()) {
            // Book cap (maxBookPicks == picksRequired when vanillaBookLimits is off).
            if (isBookCard(idx) && selectedBookCount() >= data.maxBookPicks()) {
                return;
            }
            selectedIndices.add(idx);
        }
        confirmBtn.active = (selectedIndices.size() == data.picksRequired());
        rebuildStatusText();
    }

    private int selectionNumber(int idx) {
        int n = 1;
        for (int i : selectedIndices) {
            if (i == idx) return n;
            n++;
        }
        return 0;
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
        if (level >= 1 && level <= 5) return tr("merchant.level." + level);
        return tr("tradeoptimizer.picker.level", level);
    }

    /** Vanilla's profession name; a profession with no translation falls back to its raw path. */
    private static String shortProf(String prof) {
        int idx = prof.lastIndexOf(':');
        String ns = idx >= 0 ? prof.substring(0, idx) : "minecraft";
        String path = idx >= 0 ? prof.substring(idx + 1) : prof;
        String key = "entity." + ns + ".villager." + path;
        return Language.getInstance().has(key) ? tr(key) : path.toUpperCase(Locale.ROOT);
    }
}
