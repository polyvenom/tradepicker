package com.tom.tradeoptimizer.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Identifies a trade by what's being sold. Two trades from the same villager have the
 * same signature if they sell the same item — for enchanted books, the enchantment +
 * level are part of the signature so different books are different trades.
 *
 * Used as a map key for best-price tracking and target-trade selection.
 */
public record TradeSignature(String sellItemId, String enchantmentId, int enchantmentLevel) {

    public static final TradeSignature EMPTY = new TradeSignature("", "", 0);

    public static final Codec<TradeSignature> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("sell").forGetter(TradeSignature::sellItemId),
            Codec.STRING.optionalFieldOf("ench", "").forGetter(TradeSignature::enchantmentId),
            Codec.INT.optionalFieldOf("lvl", 0).forGetter(TradeSignature::enchantmentLevel)
    ).apply(inst, TradeSignature::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeSignature> STREAM_CODEC = StreamCodec.of(
            (buf, sig) -> {
                buf.writeUtf(sig.sellItemId);
                buf.writeUtf(sig.enchantmentId);
                buf.writeVarInt(sig.enchantmentLevel);
            },
            buf -> new TradeSignature(buf.readUtf(), buf.readUtf(), buf.readVarInt())
    );

    /** Encode as a flat string for use as a map key during persistence. */
    public String encode() {
        return sellItemId + "|" + enchantmentId + "|" + enchantmentLevel;
    }

    public static TradeSignature decode(String s) {
        String[] parts = s.split("\\|", 3);
        if (parts.length < 3) return EMPTY;
        try {
            return new TradeSignature(parts[0], parts[1], Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return EMPTY;
        }
    }

    /** Derive a signature from a sell ItemStack — books capture their first enchantment. */
    public static TradeSignature of(ItemStack sell) {
        if (sell == null || sell.isEmpty()) return EMPTY;
        String sellId = BuiltInRegistries.ITEM.getKey(sell.getItem()).toString();
        if (sell.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments enchs = EnchantmentHelper.getEnchantmentsForCrafting(sell);
            if (!enchs.isEmpty()) {
                Holder<Enchantment> ench = enchs.keySet().iterator().next();
                String enchId = ench.unwrapKey()
                        .map(k -> k.identifier().toString())
                        .orElse("");
                int lvl = enchs.getLevel(ench);
                return new TradeSignature(sellId, enchId, lvl);
            }
        }
        return new TradeSignature(sellId, "", 0);
    }

    /** A human-readable label for UI display. "Mending Book", "Glass", etc. */
    public String displayName() {
        if (!enchantmentId.isEmpty()) {
            String base = enchantmentId.contains(":") ? enchantmentId.substring(enchantmentId.lastIndexOf(':') + 1) : enchantmentId;
            return capitalize(base.replace('_', ' ')) + (enchantmentLevel > 1 ? " " + romanNumeral(enchantmentLevel) : "");
        }
        if (sellItemId.isEmpty()) return "?";
        String base = sellItemId.contains(":") ? sellItemId.substring(sellItemId.lastIndexOf(':') + 1) : sellItemId;
        return capitalize(base.replace('_', ' '));
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        StringBuilder b = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) { b.append(Character.toUpperCase(c)); cap = false; }
            else b.append(c);
            if (c == ' ') cap = true;
        }
        return b.toString();
    }

    private static String romanNumeral(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            default -> String.valueOf(n);
        };
    }
}
