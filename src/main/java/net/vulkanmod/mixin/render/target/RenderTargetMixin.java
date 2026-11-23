package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.vulkanmod.render.engine.VkFbo;
import net.vulkanmod.render.engine.VkGpuTexture;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {

    @Shadow @Nullable protected GpuTexture colorTexture;
    @Shadow @Nullable protected GpuTexture depthTexture;

    @Inject(method = "blitAndBlendToTexture", at = @At("HEAD"), cancellable = true)
    private void skipUnclearedBlit(GpuTextureView colorTarget, GpuTextureView depthTarget, CallbackInfo ci) {
        RenderSystem.assertOnRenderThread();
        if (this.colorTexture instanceof VkGpuTexture vkTex) {
            VkFbo fbo = vkTex.getFbo(this.depthTexture);
            if (fbo.needsClear()) {
                ci.cancel();
            }
        }
    }
}
