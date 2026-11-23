package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(TextureAtlas.class)
public class TextureAtlasM {
    @Shadow @Final private Identifier location;
    @Shadow private List<TextureAtlasSprite> sprites;
    @Shadow private List<SpriteContents.AnimationState> animatedTexturesStates;

    @Inject(method = "upload", at = @At("TAIL"))
    private void onUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            SpriteUpdateUtil.registerBlockAtlasSprites(this.sprites, this.animatedTexturesStates);
        }
    }

    @Redirect(method = "cycleAnimationFrames", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/SpriteContents$AnimationState;tick()V"))
    private void tickIfVisible(SpriteContents.AnimationState state) {
        if (SpriteUpdateUtil.isAnimationStateActive(this.location, state)) {
            state.tick();
        }
    }
}
