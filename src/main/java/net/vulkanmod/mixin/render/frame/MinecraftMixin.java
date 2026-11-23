package net.vulkanmod.mixin.render.frame;

import net.minecraft.client.Minecraft;
import net.vulkanmod.render.texture.ImageUploadHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "renderFrame", at = @At(value = "HEAD"))
    private void submitPendingImageUploads(boolean renderLevel, CallbackInfo ci) {
        ImageUploadHelper.INSTANCE.submitCommands();
    }
}
