package fr.customblock.client;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Message envoyé au serveur (canal customblock:hello) pour annoncer que le mod est installé. */
public record HelloPayload(int version) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<HelloPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("customblock", "hello"));

	public static final net.minecraft.network.codec.StreamCodec<ByteBuf, HelloPayload> CODEC =
			CustomPacketPayload.codec(HelloPayload::write, HelloPayload::read);

	private static void write(HelloPayload payload, ByteBuf buf) {
		buf.writeInt(payload.version);
	}

	private static HelloPayload read(ByteBuf buf) {
		return new HelloPayload(buf.readInt());
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
