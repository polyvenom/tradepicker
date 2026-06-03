package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.client.state.ClientVillagerCache;
import com.tom.tradeoptimizer.trade.TradeRating;
import com.tom.tradeoptimizer.villager.OfferEntry;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TradingOptimizerScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private static final int HEADER_HEIGHT = 50;
    private static final int FOOTER_HEIGHT = 18;
    private static final int PAD = 8;

    private Tab activeTab = Tab.INDEX;
    private TextFieldWidget searchField;
    private int scrollOffset = 0;
    private final List<TradeRow> filteredRows = new ArrayList<>();

    public TradingOptimizerScreen() {
        super(Text.literal("Trade Optimizer"));
    }

    @Override
    protected void init() {
        int tabBtnWidth = 110;
        addDrawableChild(ButtonWidget.builder(Text.literal(Tab.INDEX.label),
                b -> setTab(Tab.INDEX))
                .dimensions(PAD, PAD, tabBtnWidth, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Tab.PLANNER.label),
                b -> setTab(Tab.PLANNER))
                .dimensions(PAD + tabBtnWidth + 4, PAD, tabBtnWidth, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"),
                b -> close())
                .dimensions(this.width - PAD - 60, PAD, 60, 18).build());

        searchField = new TextFieldWidget(this.textRenderer, PAD, PAD + 24,
                this.width - 2 * PAD, 18, Text.literal(""));
        searchField.setPlaceholder(Text.literal("Search item or enchantment..."));
        searchField.setMaxLength(64);
        searchField.setChangedListener(s -> {
            scrollOffset = 0;
            rebuildFilter();
        });
        addDrawableChild(searchField);

        rebuildFilter();
    }

    private void setTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.scrollOffset = 0;
        rebuildFilter();
    }

    private void rebuildFilter() {
        filteredRows.clear();
        String query = (searchField != null ? searchField.getText() : "").toLowerCase(Locale.ROOT).trim();
        BlockPos playerPos = (client != null && client.player != null) ? client.player.getBlockPos() : BlockPos.ORIGIN;

        for (VillagerEntry v : ClientVillagerCache.get()) {
            for (OfferEntry o : v.offers()) {
                if (!matches(o, query)) continue;
                double dist = Math.sqrt(playerPos.getSquaredDistance(v.pos()));
                filteredRows.add(new TradeRow(v, o, dist));
            }
        }
        filteredRows.sort((a, b) -> Double.compare(a.distance, b.distance));
    }

    private boolean matches(OfferEntry o, String query) {
        if (query.isEmpty()) return true;
        if (nameContains(o.sell(), query)) return true;
        if (nameContains(o.firstBuy(), query)) return true;
        if (nameContains(o.secondBuy(), query)) return true;
        if (o.sell().isOf(Items.ENCHANTED_BOOK)) {
            var enchants = EnchantmentHelper.getEnchantments(o.sell());
            for (var e : enchants.getEnchantments()) {
                String name = e.getIdAsString().toLowerCase(Locale.ROOT);
                if (name.contains(query)) return true;
            }
        }
        return false;
    }

    private boolean nameContains(ItemStack stack, String query) {
        if (stack.isEmpty()) return false;
        return stack.getName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawText(this.textRenderer, this.title, PAD, this.height - FOOTER_HEIGHT + 4, 0xFFAAAAAA, false);

        int contentTop = HEADER_HEIGHT;
        int contentBottom = this.height - FOOTER_HEIGHT;

        ctx.fill(PAD - 2, contentTop - 2, this.width - PAD + 2, contentBottom + 2, 0x66000000);

        ctx.enableScissor(PAD, contentTop, this.width - PAD, contentBottom);
        if (activeTab == Tab.INDEX) renderIndex(ctx, contentTop, contentBottom);
        else renderPlanner(ctx, contentTop, contentBottom);
        ctx.disableScissor();

        renderTabUnderline(ctx);
    }

    private void renderTabUnderline(DrawContext ctx) {
        int y = PAD + 20;
        if (activeTab == Tab.INDEX) ctx.fill(PAD, y, PAD + 110, y + 2, 0xFFFFFFFF);
        else ctx.fill(PAD + 114, y, PAD + 224, y + 2, 0xFFFFFFFF);
    }

    private void renderIndex(DrawContext ctx, int top, int bottom) {
        if (filteredRows.isEmpty()) {
            String msg = ClientVillagerCache.get().isEmpty()
                    ? "No villagers tracked yet. Interact with one to start."
                    : "No trades match your search.";
            ctx.drawText(this.textRenderer, msg, PAD + 6, top + 8, 0xFFCCCCCC, false);
            return;
        }
        int y = top + 4 - scrollOffset;
        for (TradeRow row : filteredRows) {
            if (y + ROW_HEIGHT > top && y < bottom) drawRow(ctx, row, y);
            y += ROW_HEIGHT;
        }
    }

    private void drawRow(DrawContext ctx, TradeRow row, int y) {
        int x = PAD + 4;
        // Profession + level + coords
        String head = String.format("%s [Lv %d]   (%d, %d, %d)   %.0fm",
                shortProfession(row.villager.profession()),
                row.villager.level(),
                row.villager.pos().getX(), row.villager.pos().getY(), row.villager.pos().getZ(),
                row.distance);
        ctx.drawText(this.textRenderer, head, x, y + 2, 0xFFFFFFFF, false);

        // Item icons (give -> take -> rating chip)
        int iconY = y + 12;
        ctx.drawItem(row.offer.firstBuy(), x, iconY);
        ctx.drawStackOverlay(this.textRenderer, row.offer.firstBuy(), x, iconY);
        ctx.drawText(this.textRenderer, "→", x + 22, iconY + 4, 0xFFAAAAAA, false);
        if (!row.offer.secondBuy().isEmpty()) {
            ctx.drawItem(row.offer.secondBuy(), x + 32, iconY);
            ctx.drawStackOverlay(this.textRenderer, row.offer.secondBuy(), x + 32, iconY);
            ctx.drawText(this.textRenderer, "→", x + 54, iconY + 4, 0xFFAAAAAA, false);
            ctx.drawItem(row.offer.sell(), x + 64, iconY);
            ctx.drawStackOverlay(this.textRenderer, row.offer.sell(), x + 64, iconY);
        } else {
            ctx.drawItem(row.offer.sell(), x + 32, iconY);
            ctx.drawStackOverlay(this.textRenderer, row.offer.sell(), x + 32, iconY);
        }

        // Rating chip
        TradeRating r = row.offer.rating();
        int chipX = this.width - PAD - 60;
        ctx.fill(chipX, y + 10, chipX + 52, y + 22, r.color());
        ctx.drawText(this.textRenderer, r.label(), chipX + 6, y + 12, 0xFF000000, false);
    }

    private String shortProfession(String prof) {
        int idx = prof.lastIndexOf(':');
        return (idx >= 0 ? prof.substring(idx + 1) : prof).toUpperCase(Locale.ROOT);
    }

    private void renderPlanner(DrawContext ctx, int top, int bottom) {
        List<VillagerEntry> all = ClientVillagerCache.get();
        Map<String, Integer> byProf = new LinkedHashMap<>();
        int assigned = 0;
        for (VillagerEntry v : all) {
            String p = shortProfession(v.profession());
            byProf.merge(p, 1, Integer::sum);
            if (!p.equalsIgnoreCase("NONE") && !p.equalsIgnoreCase("NITWIT")) assigned++;
        }

        int vacant = NearbyScanner.countVacantWorkstations(MinecraftClient.getInstance(), all);

        int y = top + 6;
        ctx.drawText(this.textRenderer, "Villagers tracked: " + all.size(), PAD + 6, y, 0xFFFFFFFF, false); y += 12;
        ctx.drawText(this.textRenderer, "Assigned: " + assigned, PAD + 6, y, 0xFFAAFFAA, false); y += 12;
        ctx.drawText(this.textRenderer, "Vacant workstations nearby (within 32 blocks): " + vacant, PAD + 6, y, 0xFFFFCC55, false);
        y += 16;
        ctx.drawText(this.textRenderer, "Per profession:", PAD + 6, y, 0xFFFFFFFF, false); y += 12;

        int col = 0;
        int colWidth = (this.width - 2 * PAD) / 3;
        int startY = y;
        for (Map.Entry<String, Integer> e : byProf.entrySet()) {
            int cx = PAD + 6 + col * colWidth;
            ctx.drawText(this.textRenderer, e.getKey() + ": " + e.getValue(), cx, y, 0xFFEEEEEE, false);
            col++;
            if (col >= 3) { col = 0; y += 12; }
        }
        if (byProf.isEmpty()) {
            ctx.drawText(this.textRenderer, "(no data)", PAD + 6, startY, 0xFFAAAAAA, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (activeTab == Tab.INDEX) {
            scrollOffset = Math.max(0, scrollOffset - (int) (verticalAmount * 18));
            int maxScroll = Math.max(0, filteredRows.size() * ROW_HEIGHT - (this.height - HEADER_HEIGHT - FOOTER_HEIGHT));
            scrollOffset = Math.min(scrollOffset, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private record TradeRow(VillagerEntry villager, OfferEntry offer, double distance) {}
}
