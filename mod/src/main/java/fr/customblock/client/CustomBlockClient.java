package fr.customblock.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CustomBlockClient implements ClientModInitializer {

	public static final String NAMESPACE = "customblock";
	private static final Logger LOGGER = LoggerFactory.getLogger("customblock_client");
	/** États d'un bloc d'escalier : 4 directions x 2 moitiés x 5 formes x eau. */
	private static final int STATES_PER_STAIR = 80;
	private static final int MAX_SLOTS = 40;

	private static final List<Block> STAIRS = new ArrayList<>();

	public static Block stairBlock(int slot) {
		return slot >= 0 && slot < STAIRS.size() ? STAIRS.get(slot) : null;
	}

	@Override
	public void onInitializeClient() {
		registerBlocks();

		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);

		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE,
				(payload, context) -> StairOverlay.handle(payload.data()));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			StairOverlay.clear();
			try {
				ClientPlayNetworking.send(new HelloPayload(1));
			} catch (RuntimeException e) {
				LOGGER.warn("Impossible d'annoncer le mod au serveur : {}", e.toString());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> StairOverlay.clear());
		ClientTickEvents.END_CLIENT_TICK.register(StairOverlay::tick);
	}

	/**
	 * Déclare les blocs d'escalier. Les identifiants d'états sont écrits dans les chunks avec un nombre de bits fixé
	 * par la taille du registre : on n'ajoute que ce qui tient dans la largeur actuelle, sinon les chunks seraient
	 * mal décodés.
	 */
	private static void registerBlocks() {
		int base = Block.BLOCK_STATE_REGISTRY.size();
		int bits = 32 - Integer.numberOfLeadingZeros(base - 1);
		int room = (1 << bits) - base;
		int count = Math.min(MAX_SLOTS, room / STATES_PER_STAIR);
		for (int slot = 0; slot < count; slot++) {
			Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "stairs_" + slot);
			BlockBehaviour.Properties props = BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS)
					.setId(ResourceKey.create(Registries.BLOCK, id));
			STAIRS.add(Registry.register(BuiltInRegistries.BLOCK, id, new CustomStairBlock(props)));
		}
		LOGGER.info("{} escalier(s) custom déclaré(s) ({} états libres avant ajout).", count, room);
	}
}
