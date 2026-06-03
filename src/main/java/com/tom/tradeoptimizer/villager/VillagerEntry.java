package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent record of a villager we've interacted with.
 *
 * `bestPrices` is the lowest emerald cost ever seen at THIS villager for each unique
 * trade signature (e.g. "Mending Book" -> 14 emeralds). Drives the BEST badge in UI.
 */
public record VillagerEntry(
        UUID id,
        String profession,
        int level,
        BlockPos pos,
        long lastSeenTick,
        List<OfferEntry> offers,
        Map<String, Integer> bestPrices
) {

    public VillagerEntry {
        // Defensive: bestPrices may be unmodifiable from codec decode; copy on construct.
        bestPrices = new HashMap<>(bestPrices);
    }

    public static final Codec<VillagerEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillagerEntry::id),
            Codec.STRING.fieldOf("prof").forGetter(VillagerEntry::profession),
            Codec.INT.fieldOf("lvl").forGetter(VillagerEntry::level),
            BlockPos.CODEC.fieldOf("pos").forGetter(VillagerEntry::pos),
            Codec.LONG.fieldOf("seen").forGetter(VillagerEntry::lastSeenTick),
            OfferEntry.CODEC.listOf().fieldOf("offers").forGetter(VillagerEntry::offers),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("best", new HashMap<>()).forGetter(VillagerEntry::bestPrices)
    ).apply(inst, VillagerEntry::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerEntry> STREAM_CODEC = StreamCodec.of(
            (buf, v) -> {
                buf.writeUUID(v.id);
                buf.writeUtf(v.profession);
                buf.writeVarInt(v.level);
                buf.writeBlockPos(v.pos);
                buf.writeVarLong(v.lastSeenTick);
                buf.writeVarInt(v.offers.size());
                for (OfferEntry o : v.offers) OfferEntry.STREAM_CODEC.encode(buf, o);
                buf.writeVarInt(v.bestPrices.size());
                for (Map.Entry<String, Integer> e : v.bestPrices.entrySet()) {
                    buf.writeUtf(e.getKey());
                    buf.writeVarInt(e.getValue());
                }
            },
            buf -> {
                UUID id = buf.readUUID();
                String prof = buf.readUtf();
                int level = buf.readVarInt();
                BlockPos pos = buf.readBlockPos();
                long seen = buf.readVarLong();
                int oCount = buf.readVarInt();
                java.util.List<OfferEntry> offers = new java.util.ArrayList<>(oCount);
                for (int i = 0; i < oCount; i++) offers.add(OfferEntry.STREAM_CODEC.decode(buf));
                int bCount = buf.readVarInt();
                Map<String, Integer> best = new HashMap<>(bCount);
                for (int i = 0; i < bCount; i++) best.put(buf.readUtf(), buf.readVarInt());
                return new VillagerEntry(id, prof, level, pos, seen, offers, best);
            }
    );

    /** Update best-price tracking with the current set of offers. Mutates the map. */
    public void recordBestPrices() {
        for (OfferEntry o : offers) {
            if (o.emeraldCost() <= 0 || o.signature().sellItemId().isEmpty()) continue;
            String key = o.signature().encode();
            Integer current = bestPrices.get(key);
            if (current == null || o.emeraldCost() < current) {
                bestPrices.put(key, o.emeraldCost());
            }
        }
    }

    public Integer bestPriceFor(com.tom.tradeoptimizer.trade.TradeSignature sig) {
        return bestPrices.get(sig.encode());
    }
}
