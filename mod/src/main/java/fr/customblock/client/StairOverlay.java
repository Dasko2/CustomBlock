package fr.customblock.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Positions des escaliers custom reçues du serveur. Chaque tick, tout escalier vanilla situé à l'une de ces
 * positions est remplacé, côté client uniquement, par le bloc d'escalier du mod (mêmes propriétés).
 */
public final class StairOverlay {

	private static final Map<Long, Integer> POSITIONS = new HashMap<>();
	private static ClientLevel lastLevel;

	private StairOverlay() {
	}

	public static void clear() {
		POSITIONS.clear();
		lastLevel = null;
	}

	/** Décode un message du serveur : 1 = ajouts, 2 = retraits, 3 = contenu complet d'un chunk. */
	public static void handle(byte[] data) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			int type = in.readUnsignedByte();
			if (type == 1) {
				int n = in.readInt();
				for (int i = 0; i < n; i++) {
					BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
					POSITIONS.put(pos.asLong(), (int) in.readShort());
				}
			} else if (type == 2) {
				int n = in.readInt();
				for (int i = 0; i < n; i++) {
					POSITIONS.remove(new BlockPos(in.readInt(), in.readInt(), in.readInt()).asLong());
				}
			} else if (type == 3) {
				int cx = in.readInt();
				int cz = in.readInt();
				POSITIONS.keySet().removeIf(key -> {
					BlockPos pos = BlockPos.of(key);
					return (pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz;
				});
				int n = in.readInt();
				for (int i = 0; i < n; i++) {
					BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
					POSITIONS.put(pos.asLong(), (int) in.readShort());
				}
			}
		} catch (IOException ignored) {
			// message tronqué : ignoré
		}
	}

	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			clear();
			return;
		}
		if (level != lastLevel) {
			POSITIONS.clear();
			lastLevel = level;
			return;
		}
		if (POSITIONS.isEmpty()) return;

		BlockPos playerPos = client.player.blockPosition();
		List<Long> far = new ArrayList<>();
		for (Iterator<Map.Entry<Long, Integer>> it = POSITIONS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, Integer> entry = it.next();
			BlockPos pos = BlockPos.of(entry.getKey());
			if (Math.abs(pos.getX() - playerPos.getX()) > 400 || Math.abs(pos.getZ() - playerPos.getZ()) > 400) {
				far.add(entry.getKey());
				continue;
			}
			if (!level.hasChunkAt(pos)) continue;

			Block target = CustomBlockClient.stairBlock(entry.getValue());
			if (target == null) continue;
			BlockState state = level.getBlockState(pos);
			Block current = state.getBlock();
			if (current == target || !(current instanceof StairBlock) || current instanceof CustomStairBlock) {
				if (current instanceof CustomStairBlock && current != target) {
					level.setBlock(pos, copy(target, state), 18);
				}
				continue;
			}
			level.setBlock(pos, copy(target, state), 18);
		}
		for (Long key : far) POSITIONS.remove(key);
	}

	private static BlockState copy(Block target, BlockState from) {
		return target.defaultBlockState()
				.setValue(StairBlock.FACING, from.getValue(StairBlock.FACING))
				.setValue(StairBlock.HALF, from.getValue(StairBlock.HALF))
				.setValue(StairBlock.SHAPE, from.getValue(StairBlock.SHAPE))
				.setValue(StairBlock.WATERLOGGED, from.getValue(StairBlock.WATERLOGGED));
	}
}
