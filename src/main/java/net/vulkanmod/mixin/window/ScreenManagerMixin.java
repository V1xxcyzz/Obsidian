package net.vulkanmod.mixin.window;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.MonitorManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.vulkanmod.config.video.VideoModeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MonitorManager.class)
public class ScreenManagerMixin {

    @Shadow
    @Final
    private Int2ObjectMap<Monitor> monitors;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void getMonitors(CallbackInfo ci) {
        VideoModeManager.init(this.monitors);
    }

    @Inject(method = "onDisplayConnected", at = @At("RETURN"))
    private void onDisplayConnected(int displayId, CallbackInfo ci) {
        VideoModeManager.addMonitorVideoModes(displayId);
    }

    @Inject(method = "onDisplayDisconnected", at = @At("RETURN"))
    private void onDisplayDisconnected(int displayId, CallbackInfo ci) {
        VideoModeManager.removeMonitor(displayId);
    }
}
