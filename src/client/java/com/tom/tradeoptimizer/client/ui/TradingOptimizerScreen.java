package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.client.state.ClientVillagerCache;
import com.tom.tradeoptimizer.trade.TradeRating;
import com.tom.tradeoptimizer.villager.OfferEntry;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TradingOptimizerScreen extends Screen {
    private Tab activeTab = Tab.INDEX;
    private EditBox searchField;
    private int scrollOffset = 0;
    private final List<TradeRow> filteredRows = new ArrayList<>();
    
    // UI Widget Pool (Bypasses the missing GuiGraphics pipeline)
    private final List<Button> displayRows = new ArrayList<>();

    public TradingOptimizerScreen() {
        super(Component.literal("Trade Optimizer"));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        displayRows.clear();

        int tabBtnWidth = 110;
        addRenderableWidget(Button.builder(Component.literal("Trade Index"), b -> setTab(Tab.INDEX)).bounds(8, 8, tabBtnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Profession Planner"), b -> setTab(Tab.PLANNER)).bounds(8 + tabBtnWidth + 4, 8, tabBtnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(this.width - 8 - 60, 8, 60, 20).build());

        searchField = new EditBox(this.font, 8, 36, this.width - 16, 20, Component.literal(""));
        searchField.setMaxLength(64);
        searchField.setResponder(s -> {
            scrollOffset = 0;
            recalculateData();
        });
        addRenderableWidget(searchField);

        // Pre-allocate 15 static row widgets to let the engine handle text rendering internally
        for (int i = 0; i < 15; i++) {
            Button rowBtn = Button.builder(Component.literal(""), b -> {}).bounds(8, 64 + (i * 22), this.width - 16, 20).build();
            rowBtn.active = false;
            rowBtn.visible = false;
            displayRows.add(rowBtn);
            addRenderableWidget(rowBtn);
        }

        recalculateData();
    }

    private void setTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.scrollOffset = 0;
        recalculateData();
    }

    private void recalculateData() {
        filteredRows.clear();
        if (activeTab == Tab.INDEX) {
            searchField.visible = true;
            String query = searchField.getValue().toLowerCase(Locale.ROOT).trim();
            BlockPos playerPos = (minecraft != null && minecraft.player != null) ? minecraft.player.blockPosition() : BlockPos.ZERO;

            for (VillagerEntry v : ClientVillagerCache.get()) {
                for (OfferEntry o : v.offers()) {
                    if (!matches(o, query)) continue;
                    double dist = Math.sqrt(playerPos.distSqr(v.pos()));
                    filteredRows.add(new TradeRow(v, o, dist));
                }
            }
            filteredRows.sort((a, b) -> Double.compare(a.distance, b.distance));
        }
        updateScreenView();
    }

    private void updateScreenView() {
        if (activeTab == Tab.INDEX) {
            for (int i = 0; i < displayRows.size(); i++) {
                Button btn = displayRows.get(i);
                int dataIndex = scrollOffset + i;
                if (dataIndex < filteredRows.size()) {
                    TradeRow row = filteredRows.get(dataIndex);
                    String prof = row.villager.profession();
                    prof = prof.substring(prof.lastIndexOf(':') + 1).toUpperCase(Locale.ROOT);
                    String text = String.format("[%s]  %s \u2192 %s  (%s)  %.0fm",
                            prof,
                            row.offer.firstBuy().getHoverName().getString(),
                            row.offer.sell().getHoverName().getString(),
                            row.offer.rating().name(),
                            row.distance);
                    btn.setMessage(Component.literal(text));
                    btn.visible = true;
                } else {
                    btn.visible = false;
                }
            }
        } else {
            searchField.visible = false;
            List<VillagerEntry> all = ClientVillagerCache.get();
            Map<String, Integer> byProf = new LinkedHashMap<>();
            for (VillagerEntry v : all) {
                String p = v.profession();
                p = p.substring(p.lastIndexOf(':') + 1).toUpperCase(Locale.ROOT);
                byProf.merge(p, 1, Integer::sum);
            }

            int index = 0;
            if (index >= scrollOffset && index - scrollOffset < displayRows.size()) {
                displayRows.get(index - scrollOffset).setMessage(Component.literal("Total Tracked Villagers: " + all.size()));
                displayRows.get(index - scrollOffset).visible = true;
            }
            index++;
            
            for (Map.Entry<String, Integer> e : byProf.entrySet()) {
                if (index >= scrollOffset && index - scrollOffset < displayRows.size()) {
                    displayRows.get(index - scrollOffset).setMessage(Component.literal("  " + e.getKey() + ": " + e.getValue()));
                    displayRows.get(index - scrollOffset).visible = true;
                }
                index++;
            }
            
            // Hide remaining unused rows
            for (int j = Math.max(0, index - scrollOffset); j < displayRows.size(); j++) {
                displayRows.get(j).visible = false;
            }
        }
    }

    private boolean matches(OfferEntry o, String query) {
        if (query.isEmpty()) return true;
        if (nameContains(o.sell(), query)) return true;
        if (nameContains(o.firstBuy(), query)) return true;
        if (nameContains(o.secondBuy(), query)) return true;
        
        if (o.sell().is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(o.sell());
            for (Holder<Enchantment> e : enchants.keySet()) {
                String name = e.unwrapKey().map(Object::toString).orElse("").toLowerCase(Locale.ROOT);
                if (name.contains(query)) return true;
            }
        }
        return false;
    }

    private boolean nameContains(ItemStack stack, String query) {
        if (stack.isEmpty()) return false;
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = activeTab == Tab.PLANNER ? 10 : Math.max(0, filteredRows.size() - displayRows.size() + 2);
        scrollOffset = Math.max(0, scrollOffset - (int) Math.signum(scrollY));
        scrollOffset = Math.min(scrollOffset, maxScroll);
        updateScreenView();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record TradeRow(VillagerEntry villager, OfferEntry offer, double distance) {}
}