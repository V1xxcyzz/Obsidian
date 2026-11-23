package net.vulkanmod.render.chunk.build.frapi.render;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

public class SimpleBlockRenderContext implements AltModelBlockRenderer {
    private final boolean ambientOcclusion;
    private final boolean shade;
    private final BlockColors blockColors;
    private final RandomSource random = RandomSource.create();

    public SimpleBlockRenderContext(boolean ambientOcclusion, boolean shade, BlockColors blockColors) {
        this.ambientOcclusion = ambientOcclusion;
        this.shade = shade;
        this.blockColors = blockColors;
    }

    @Override
    public void tesselateBlock(QuadEmitter output, float x, float y, float z, BlockAndTintGetter world, BlockPos pos, BlockState state, BlockStateModel model, long seed) {
        this.random.setSeed(seed);
        if (x != 0.0f || y != 0.0f || z != 0.0f) {
            output.pushTransform(quad -> {
                for (int i = 0; i < 4; i++) {
                    quad.pos(i, quad.x(i) + x, quad.y(i) + y, quad.z(i) + z);
                }
                return true;
            });
            model.emitQuads(output, world, pos, state, this.random, dir -> false);
            output.popTransform();
        } else {
            model.emitQuads(output, world, pos, state, this.random, dir -> false);
        }
    }
}
