package net.vulkanmod.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void onSetupFog(Camera camera, int i, DeltaTracker deltaTracker, float f, ClientLevel clientLevel,
                            CallbackInfoReturnable<FogData> cir) {
        FogData fogData = cir.getReturnValue();
        VRenderSystem.fogData = fogData;
        if (fogData != null && fogData.color != null) {
            VRenderSystem.setShaderFogColor(fogData.color.x(), fogData.color.y(), fogData.color.z(), fogData.color.w());
        }
    }
}
