package net.vulkanmod.mixin.window;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.option.Options;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Final @Shadow private long handle;

    @Shadow public abstract void setExclusiveFullscreen(boolean exclusiveFullscreen);
    @Shadow public abstract void setPreferredFullscreenVideoMode(Optional<VideoMode> videoMode);
    @Shadow public abstract void changeFullscreenVideoMode();
    @Shadow public abstract void setFullscreen(boolean fullscreen);

    @Inject(
            method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V",
            at = @At("RETURN")
    )
    private void getHandle(
            WindowEventHandler eventHandler,
            DisplayData displayData,
            String fullscreenVideoModeString,
            boolean exclusiveFullscreen,
            String title,
            MonitorManager monitorManager,
            GpuBackend gpuBackend,
            int flags,
            CallbackInfo ci
    ) {
        VRenderSystem.setWindow(this.handle);
    }

    @Inject(method = "updateFullscreenIfChanged", at = @At("HEAD"))
    private void applyObsidianWindowMode(CallbackInfo ci) {
        if (Options.fullscreenDirty) {
            Options.fullscreenDirty = false;
            Config config = Initializer.CONFIG;
            boolean exclusive = config.windowMode == WindowMode.EXCLUSIVE_FULLSCREEN.mode;
            boolean borderless = config.windowMode == WindowMode.WINDOWED_FULLSCREEN.mode;
            this.setExclusiveFullscreen(exclusive);
            if (exclusive && config.videoMode != null && config.videoMode.width > 0 && config.videoMode.height > 0) {
                this.setPreferredFullscreenVideoMode(Optional.of(new VideoMode(
                        config.videoMode.width,
                        config.videoMode.height,
                        config.videoMode.bitDepth,
                        config.videoMode.bitDepth,
                        config.videoMode.bitDepth,
                        config.videoMode.refreshRate
                )));
                this.changeFullscreenVideoMode();
            }
            this.setFullscreen(exclusive || borderless);
        }
    }

    @Inject(method = "onFramebufferResize", at = @At("RETURN"))
    private void onFramebufferResize(int width, int height, CallbackInfo ci) {
        if (width > 0 && height > 0) {
            Renderer.scheduleSwapChainUpdate();
        }
    }

    @Inject(method = "onResize", at = @At("RETURN"))
    private void onResize(int width, int height, CallbackInfo ci) {
        if (width > 0 && height > 0) {
            Renderer.scheduleSwapChainUpdate();
        }
    }
}
