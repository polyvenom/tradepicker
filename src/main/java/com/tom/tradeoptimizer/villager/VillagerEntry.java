package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.UUID;

public record VillagerEntry(
        UUID id,
        String profession,
        int level,
        BlockPos pos,
        long lastSeenTick,
        List<OfferEntry> offers
) {
    public static final Codec<VillagerEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillagerEntry::id),
            Codec.STRING.fieldOf("prof").forGetter(VillagerEntry::profession),
            Codec.INT.fieldOf("lvl").forGetter(VillagerEntry::level),
            BlockPos.CODEC.fieldOf("pos").forGetter(VillagerEntry::pos),
            Codec.LONG.fieldOf("seen").forGetter(VillagerEntry::lastSeenTick),
            OfferEntry.CODEC.listOf().fieldOf("offers").forGetter(VillagerEntry::offers)
    ).apply(inst, VillagerEntry::new));
}