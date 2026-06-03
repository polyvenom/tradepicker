package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tom.tradeoptimizer.trade.TradeRating;
import com.tom.tradeoptimizer.trade.TradeSignature;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * A single trade row for our records.
 *
 * `emeraldCost` is the buy-side emerald amount (when villager sells you something).
 * For villager-buys-from-you trades it's 0 since we read it from the result/sell side.
 */
public record OfferEntry(
        ItemStack firstBuy,
        ItemStack secondBuy,
        ItemStack sell,
        int uses,
        int maxUses,
        boolean outOfStock,
        TradeRating rating,
        int emeraldCost,
        TradeSignature signature
) {

    public static final Codec<OfferEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ItemStack.OPTIONAL_CODEC.fieldOf("first").forGetter(OfferEntry::firstBuy),
            ItemStack.OPTIONAL_CODEC.fieldOf("second").forGetter(OfferEntry::secondBuy),
            ItemStack.OPTIONAL_CODEC.fieldOf("sell").forGetter(OfferEntry::sell),
            Codec.INT.fieldOf("uses").forGetter(OfferEntry::uses),
            Codec.INT.fieldOf("max").forGetter(OfferEntry::maxUses),
            Codec.BOOL.fieldOf("oos").forGetter(OfferEntry::outOfStock),
            Codec.STRING.xmap(OfferEntry::ratingFromName, Enum::name)
                    .optionalFieldOf("rating", TradeRating.UNKNOWN).forGetter(OfferEntry::rating),
            Codec.INT.optionalFieldOf("cost", 0).forGetter(OfferEntry::emeraldCost),
            TradeSignature.CODEC.optionalFieldOf("sig", TradeSignature.EMPTY).forGetter(OfferEntry::signature)
    ).apply(inst, OfferEntry::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, OfferEntry> STREAM_CODEC = StreamCodec.of(
            (buf, o) -> {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.firstBuy);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.secondBuy);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.sell);
                buf.writeVarInt(o.uses);
                buf.writeVarInt(o.maxUses);
                buf.writeBoolean(o.outOfStock);
                buf.writeByte(o.rating.ordinal());
                buf.writeVarInt(o.emeraldCost);
                TradeSignature.STREAM_CODEC.encode(buf, o.signature);
            },
            buf -> {
                ItemStack a = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                ItemStack b = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                ItemStack s = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int u = buf.readVarInt();
                int m = buf.readVarInt();
                boolean d = buf.readBoolean();
                int rOrd = buf.readByte() & 0xFF;
                TradeRating r = rOrd < TradeRating.values().length ? TradeRating.values()[rOrd] : TradeRating.UNKNOWN;
                int cost = buf.readVarInt();
                TradeSignature sig = TradeSignature.STREAM_CODEC.decode(buf);
                return new OfferEntry(a, b, s, u, m, d, r, cost, sig);
            }
    );

    private static TradeRating ratingFromName(String s) {
        try { return TradeRating.valueOf(s); } catch (IllegalArgumentException e) { return TradeRating.UNKNOWN; }
    }

    public static OfferEntry fromMerchantOffer(MerchantOffer offer, TradeRating rating) {
        int cost = 0;
        ItemStack first = offer.getBaseCostA();
        ItemStack second = offer.getCostB();
        if (first.is(Items.EMERALD)) cost = first.getCount();
        else if (second.is(Items.EMERALD)) cost = second.getCount();
        return new OfferEntry(
                first.copy(),
                second.copy(),
                offer.getResult().copy(),
                offer.getUses(),
                offer.getMaxUses(),
                offer.isOutOfStock(),
                rating,
                cost,
                TradeSignature.of(offer.getResult())
        );
    }
}
