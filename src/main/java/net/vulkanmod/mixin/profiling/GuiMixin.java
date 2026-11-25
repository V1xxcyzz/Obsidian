package net.vulkanmod.mixin.profiling;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.profiling.HudOverlay;
import net.vulkanmod.render.profiling.ProfilerOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void createProfilerOverlay(Minecraft minecraft, Hud hud, GuiRenderState guiRenderState, CallbackInfo ci) {
        ProfilerOverlay.createInstance(minecraft);
    }

    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER))
    private void renderProfilerOverlay(DeltaTracker deltaTracker, boolean hudVisible, boolean screenVisible, CallbackInfo ci, @Local GuiGraphicsExtractor guiGraphics) {
        if (!this.minecraft.getDebugOverlay().showDebugScreen()) {
            if (ProfilerOverlay.shouldRender) {
                ProfilerOverlay.INSTANCE.render(guiGraphics);
            } else if (HudOverlay.shouldRender() && hudVisible && !screenVisible && this.minecraft.level != null) {
                HudOverlay.render(guiGraphics, Initializer.CONFIG.hudMode);
            }
        }
    }
}
