package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.TradeRating;
import com.tom.tradeoptimizer.villager.OfferEntry;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record VillagerSyncS2C(List<VillagerEntry> villagers) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerSyncS2C> CODEC = StreamCodec.of(
            VillagerSyncS2C::write,
            VillagerSyncS2C::read
    );

    public static VillagerSyncS2C of(Collection<VillagerEntry> all) {
        return new VillagerSyncS2C(new ArrayList<>(all));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.SYNC_ID;
    }

    private static void write(RegistryFriendlyByteBuf buf, VillagerSyncS2C value) {
        buf.writeVarInt(value.villagers.size());
        for (VillagerEntry v : value.villagers) writeVillager(buf, v);
    }

    private static VillagerSyncS2C read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<VillagerEntry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(readVillager(buf));
        return new VillagerSyncS2C(list);
    }

    private static void writeVillager(RegistryFriendlyByteBuf buf, VillagerEntry v) {
        buf.writeUUID(v.id());
        buf.writeUtf(v.profession());
        buf.writeVarInt(v.level());
        buf.writeBlockPos(v.pos());
        buf.writeVarLong(v.lastSeenTick());
        buf.writeVarInt(v.offers().size());
        for (OfferEntry o : v.offers()) writeOffer(buf, o);
    }

    private static VillagerEntry readVillager(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String prof = buf.readUtf();
        int level = buf.readVarInt();
        BlockPos pos = buf.readBlockPos();
        long seen = buf.readVarLong();
        int n = buf.readVarInt();
        List<OfferEntry> offers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) offers.add(readOffer(buf));
        return new VillagerEntry(id, prof, level, pos, seen, offers);
    }

    private static void writeOffer(RegistryFriendlyByteBuf buf, OfferEntry o) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.firstBuy());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.secondBuy());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.sell());
        buf.writeVarInt(o.uses());
        buf.writeVarInt(o.maxUses());
        buf.writeBoolean(o.disabled());
        buf.writeByte(o.rating().ordinal());
    }

    private static OfferEntry readOffer(RegistryFriendlyByteBuf buf) {
        ItemStack first = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack second = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack sell = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        int uses = buf.readVarInt();
        int max = buf.readVarInt();
        boolean disabled = buf.readBoolean();
        int ratingOrd = buf.readByte() & 0xFF;
        TradeRating rating = ratingOrd < TradeRating.values().length
                ? TradeRating.values()[ratingOrd]
                : TradeRating.UNKNOWN;
        return new OfferEntry(first, second, sell, uses, max, disabled, rating);
    }
}