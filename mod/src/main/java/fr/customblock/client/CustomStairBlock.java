package fr.customblock.client;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;

/** Escalier déclaré par le mod : même comportement que l'escalier de chêne, apparence fournie par le resource pack. */
public class CustomStairBlock extends StairBlock {
	public CustomStairBlock(Properties properties) {
		super(Blocks.OAK_PLANKS.defaultBlockState(), properties);
	}
}
