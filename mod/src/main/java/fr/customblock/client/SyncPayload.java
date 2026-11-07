package fr.customblock.client;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Message du serveur (canal customblock:sync) : contenu brut, décodé par {@link StairOverlay}. */
public record SyncPayload(byte[] data) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("customblock", "sync"));

	public static final net.minecraft.network.codec.StreamCodec<ByteBuf, SyncPayload> CODEC =
			CustomPacketPayload.codec(SyncPayload::write, SyncPayload::read);

	private static void write(SyncPayload payload, ByteBuf buf) {
		buf.writeBytes(payload.data);
	}

	private static SyncPayload read(ByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		return new SyncPayload(bytes);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
