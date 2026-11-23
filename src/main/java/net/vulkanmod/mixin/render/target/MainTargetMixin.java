package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {

    public MainTargetMixin(int width, int height) {
        super("Main", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
    }

    /**
     * @author Obsidian
     * @reason SwapChain framebuffer is managed by Renderer
     */
    @Overwrite
    private void createFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void createBuffers(int width, int height) {
        RenderSystem.assertOnRenderThread();
        GpuDevice gpuDevice = RenderSystem.getDevice();
        int maxTexSize = gpuDevice.getDeviceInfo().limits().maxTextureSize();
        if (width > 0 && width <= maxTexSize && height > 0 && height <= maxTexSize) {
            this.width = width;
            this.height = height;
            if (this.depthFormat != null) {
                this.depthTexture = gpuDevice.createTexture(() -> this.label + " / Depth", 15, this.depthFormat, width, height, 1, 1);
                this.depthTextureView = gpuDevice.createTextureView(this.depthTexture);
            }

            this.colorTexture = gpuDevice.createTexture(() -> this.label + " / Color", 15, this.colorFormat, width, height, 1, 1);
            this.colorTextureView = gpuDevice.createTextureView(this.colorTexture);
        } else {
            throw new IllegalArgumentException("Window " + width + "x" + height + " size out of bounds (max. size: " + maxTexSize + ")");
        }
    }

    @Override
    public GpuTexture getColorTexture() {
        return Renderer.getInstance().getMainPass().getColorAttachment();
    }

    @Override
    public GpuTextureView getColorTextureView() {
        return Renderer.getInstance().getMainPass().getColorAttachmentView();
    }

    @Override
    public GpuTexture getDepthTexture() {
        return Renderer.getInstance().getMainPass().getDepthAttachment();
    }

    @Override
    public GpuTextureView getDepthTextureView() {
        return Renderer.getInstance().getMainPass().getDepthAttachmentView();
    }
}
