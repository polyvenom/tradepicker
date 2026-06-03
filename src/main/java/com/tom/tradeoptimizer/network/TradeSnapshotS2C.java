package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;

/**
 * Server -> client: snapshot of the villager the player just opened trades with,
 * plus the player's persistent history (best prices) at that villager.
 *
 * If `villager` is empty, the client should clear its overlay state.
 */
public record TradeSnapshotS2C(Optional<VillagerEntry> villager) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeSnapshotS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.villager.isPresent());
                p.villager.ifPresent(v -> VillagerEntry.STREAM_CODEC.encode(buf, v));
            },
            buf -> buf.readBoolean()
                    ? new TradeSnapshotS2C(Optional.of(VillagerEntry.STREAM_CODEC.decode(buf)))
                    : new TradeSnapshotS2C(Optional.empty())
    );

    public static TradeSnapshotS2C of(VillagerEntry v) {
        return new TradeSnapshotS2C(Optional.of(v));
    }

    public static TradeSnapshotS2C empty() {
        return new TradeSnapshotS2C(Optional.empty());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.SNAPSHOT_TYPE;
    }
}
