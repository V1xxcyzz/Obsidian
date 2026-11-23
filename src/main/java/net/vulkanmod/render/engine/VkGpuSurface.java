package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import java.util.Collection;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;

import static org.lwjgl.vulkan.VK10.*;

public class VkGpuSurface implements GpuSurfaceBackend {
    private final VkGpuDevice device;
    private final long windowHandle;
    private final BooleanSupplier shouldClose;
    private boolean configured;
    private int lastWidth;
    private int lastHeight;

    public VkGpuSurface(VkGpuDevice device, long windowHandle, BooleanSupplier shouldClose) {
        this.device = device;
        this.windowHandle = windowHandle;
        this.shouldClose = shouldClose;
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO
                || config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
        Vulkan.setVsync(vsync);

        if (!this.configured || this.lastWidth != config.width() || this.lastHeight != config.height()) {
            this.lastWidth = config.width();
            this.lastHeight = config.height();
            this.configured = true;
            Renderer.scheduleSwapChainUpdate();
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.shouldClose.getAsBoolean()) {
            return;
        }
        Renderer.getInstance().beginFrame();
        this.device.getEncoder().transientMemory().beginFrame(Renderer.getCurrentFrame());
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView view) {
        if (view == null || view.isClosed()) {
            return;
        }
        if (Renderer.getInstance().getMainPass().getColorAttachment() == view.texture()) {
            // MainPass already renders directly to the swapchain image
            return;
        }

        if (!(view instanceof VkTextureView vkView) || !Renderer.isRecording()) {
            return;
        }

        Renderer.getInstance().endRenderPass();
        VulkanImage srcImage = vkView.texture().getVulkanImage();
        VulkanImage dstImage = Renderer.getInstance().getMainPass().getMainFramebuffer().getColorAttachment();
        if (srcImage == null || dstImage == null || srcImage == dstImage) {
            return;
        }

        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int prevSrcLayout = srcImage.getCurrentLayout();
            srcImage.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstImage.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(srcImage.aspect).mipLevel(view.baseMipLevel()).baseArrayLayer(0).layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(srcImage.width, srcImage.height, 1);

            region.dstSubresource().aspectMask(dstImage.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffsets(0).set(0, 0, 0);
            region.dstOffsets(1).set(dstImage.width, dstImage.height, 1);

            vkCmdBlitImage(
                    cmd,
                    srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region,
                    VK_FILTER_LINEAR
            );

            if (prevSrcLayout != VK_IMAGE_LAYOUT_UNDEFINED && prevSrcLayout != VK_IMAGE_LAYOUT_PREINITIALIZED) {
                srcImage.transitionImageLayout(stack, cmd, prevSrcLayout);
            } else {
                srcImage.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            dstImage.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
    }

    @Override
    public void present() {
        Renderer.getInstance().endFrame();
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return EnumSet.of(
                GpuSurface.PresentMode.FIFO,
                GpuSurface.PresentMode.MAILBOX,
                GpuSurface.PresentMode.IMMEDIATE
        );
    }

    @Override
    public void close() {
    }
}
