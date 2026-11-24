package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void skipSkyRender(GpuBufferSlice skyUniforms, SkyRenderState state, CallbackInfo ci) {
        if (Initializer.CONFIG != null && Initializer.CONFIG.skyRenderMode == 2) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void skipStarsRender(float starBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (Initializer.CONFIG != null && Initializer.CONFIG.skyRenderMode >= 1) {
            ci.cancel();
        }
    }
}
