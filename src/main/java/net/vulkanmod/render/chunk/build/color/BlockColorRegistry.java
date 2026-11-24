package net.vulkanmod.render.chunk.build.color;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class BlockColorRegistry {

	private final Reference2ReferenceOpenHashMap<Block, BlockTintSource[]> map = new Reference2ReferenceOpenHashMap<>();

	public void register(List<BlockTintSource> blockColors, Block... blocks) {
		BlockTintSource[] array = blockColors.toArray(BlockTintSource[]::new);
		for (Block block : blocks) {
			this.map.put(block, array);
		}
	}

	public BlockTintSource getBlockColor(Block block, int tintIndex) {
		BlockTintSource[] sources = this.map.get(block);
		if (sources == null || tintIndex < 0 || tintIndex >= sources.length) {
			return null;
		}
		return sources[tintIndex];
	}

}
