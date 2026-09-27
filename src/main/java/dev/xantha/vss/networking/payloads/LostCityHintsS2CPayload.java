package dev.xantha.vss.networking.payloads;

import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Building footprints and heights; real Voxy columns supersede these previews. */
public record LostCityHintsS2CPayload(ResourceLocation dimension, int regionX, int regionZ,
                                      long session, boolean active, int[] chunks) implements CustomPacketPayload {
    public static final int REGION_CHUNKS = 8;
    public static final int ENTRY_COUNT = REGION_CHUNKS * REGION_CHUNKS;
    public static final Type<LostCityHintsS2CPayload> TYPE = VSSPayloadCodecs.type("lost_city_hints");
    public static final StreamCodec<RegistryFriendlyByteBuf, LostCityHintsS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(LostCityHintsS2CPayload::encode, LostCityHintsS2CPayload::decode);

    public LostCityHintsS2CPayload {
        chunks = chunks == null ? new int[0] : Arrays.copyOf(chunks, chunks.length);
        if (active && chunks.length != ENTRY_COUNT || !active && chunks.length != 0)
            throw new IllegalArgumentException("Invalid Lost Cities hint count");
    }

    @Override public int[] chunks() { return Arrays.copyOf(chunks, chunks.length); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void encode(LostCityHintsS2CPayload payload, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeInt(payload.regionX);
        buffer.writeInt(payload.regionZ);
        buffer.writeLong(payload.session);
        buffer.writeBoolean(payload.active);
        if (payload.active) for (int value : payload.chunks) buffer.writeInt(value);
    }

    private static LostCityHintsS2CPayload decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        int regionX = buffer.readInt(), regionZ = buffer.readInt();
        long session = buffer.readLong();
        boolean active = buffer.readBoolean();
        int[] chunks = active ? new int[ENTRY_COUNT] : new int[0];
        for (int i = 0; i < chunks.length; i++) chunks[i] = buffer.readInt();
        return new LostCityHintsS2CPayload(dimension, regionX, regionZ, session, active, chunks);
    }
}
