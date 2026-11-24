package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.GraphicsQueue;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;
import org.lwjgl.vulkan.VkImageCopy;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VkCommandEncoder implements CommandEncoderBackend {
    private final VkGpuDevice device;
    private final VkTransientMemory transientMemory = new VkTransientMemory();

    private static final LongBuffer VERTEX_BUFFERS = MemoryUtil.memAllocLong(VkRenderPass.MAX_VERTEX_BUFFERS);
    private static final LongBuffer VERTEX_OFFSETS = MemoryUtil.memAllocLong(VkRenderPass.MAX_VERTEX_BUFFERS);
    private static final long P_VERTEX_BUFFERS = MemoryUtil.memAddress0(VERTEX_BUFFERS);
    private static final long P_VERTEX_OFFSETS = MemoryUtil.memAddress0(VERTEX_OFFSETS);

    @Nullable
    private VkBackendRenderPipeline lastPipeline;
    @Nullable
    private VkRenderPass activeRenderPass;
    private boolean inRenderPass;

    private final int framebufferId = VkGlFramebuffer.genFramebufferId();

    private VkCommandBuffer lastCmdBuffer;
    private int lastBufferBindEpoch = -1;
    private final long[] boundVertexBufferIds = new long[VkRenderPass.MAX_VERTEX_BUFFERS];
    private final long[] boundVertexOffsets = new long[VkRenderPass.MAX_VERTEX_BUFFERS];
    private long boundIndexBufferId = 0L;
    private long boundIndexOffset = -1L;
    private int boundIndexType = -1;

    private CommandPool.CommandBuffer trackedTransferCb;
    private final LongOpenHashSet dirtyTransferBuffers = new LongOpenHashSet();

    protected VkCommandEncoder(VkGpuDevice device) {
        this.device = device;
        for (int i = 0; i < VkRenderPass.MAX_VERTEX_BUFFERS; i++) {
            this.boundVertexOffsets[i] = -1L;
        }
    }

    public void invalidateCachedState() {
        this.lastPipeline = null;
        this.lastCmdBuffer = null;
        this.lastBufferBindEpoch = -1;
        for (int i = 0; i < VkRenderPass.MAX_VERTEX_BUFFERS; i++) {
            this.boundVertexBufferIds[i] = 0L;
            this.boundVertexOffsets[i] = -1L;
        }
        this.boundIndexBufferId = 0L;
        this.boundIndexOffset = -1L;
        this.boundIndexType = -1;
    }

    private void resetBufferCache() {
        for (int i = 0; i < VkRenderPass.MAX_VERTEX_BUFFERS; i++) {
            this.boundVertexBufferIds[i] = 0L;
            this.boundVertexOffsets[i] = -1L;
        }
        this.boundIndexBufferId = 0L;
        this.boundIndexOffset = -1L;
        this.boundIndexType = -1;
    }

    private void trackTransferCb(CommandPool.CommandBuffer transferCb) {
        if (this.trackedTransferCb != transferCb || !transferCb.isRecording()) {
            this.trackedTransferCb = transferCb;
            this.dirtyTransferBuffers.clear();
        }
    }

    @Override
    public void submit() {
        if (this.inRenderPass) {
            this.submitRenderPass();
        }
        if (Renderer.isRecording()) {
            Renderer.getInstance().endRenderPass();
        }
    }

    @Override
    public VkTransientMemory transientMemory() {
        return this.transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }

        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = null;
        for (var att : descriptor.colorAttachments()) {
            if (att != null) {
                colorAttachment = att;
                break;
            }
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();

        GpuTextureView colorView = colorAttachment != null ? colorAttachment.textureView() : null;
        GpuTextureView depthView = depthAttachment != null ? depthAttachment.textureView() : null;

        boolean isMainTarget = colorView == null
                || Renderer.getInstance().getMainPass().getColorAttachment() == colorView.texture();

        this.inRenderPass = true;
        this.lastPipeline = null;

        if (isMainTarget) {
            this.resetBufferCache();
            Renderer.getInstance().getMainPass().rebindMainTarget();

            int clearFlags = 0;
            if (colorAttachment != null && colorAttachment.clearValue().isPresent()) {
                Vector4fc c = colorAttachment.clearValue().get();
                VRenderSystem.setClearColor(c.x(), c.y(), c.z(), c.w());
                clearFlags |= 0x4000;
            }
            if (depthAttachment != null && depthAttachment.clearValue().isPresent()) {
                VRenderSystem.clearDepth(depthAttachment.clearValue().getAsDouble());
                clearFlags |= 0x100;
            }
            if (clearFlags != 0) {
                Renderer.resetScissor();
                VRenderSystem.depthMask = true;
                VRenderSystem.colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);
                Renderer.clearAttachments(clearFlags);
            }
        } else {
            GpuTexture depthTex = depthView != null ? depthView.texture() : null;
            VkFbo fbo = ((VkTextureView) colorView).getFbo(depthTex);
            fbo.bind();

            int clearFlags = 0;
            if (colorAttachment.clearValue().isPresent()) {
                Vector4fc c = colorAttachment.clearValue().get();
                VRenderSystem.setClearColor(c.x(), c.y(), c.z(), c.w());
                clearFlags |= 0x4000;
            }
            if (depthAttachment != null && depthAttachment.clearValue().isPresent()) {
                VRenderSystem.clearDepth(depthAttachment.clearValue().getAsDouble());
                clearFlags |= 0x100;
            }
            if (clearFlags != 0) {
                Renderer.resetScissor();
                VRenderSystem.depthMask = true;
                VRenderSystem.colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);
                Renderer.clearAttachments(clearFlags);
            }

            RenderPass.RenderArea area = descriptor.renderArea();
            if (area != null) {
                Renderer.setViewport(area.x(), area.y(), area.width(), area.height());
            } else {
                Renderer.setViewport(0, 0, colorView.getWidth(0), colorView.getHeight(0));
            }
        }

        this.activeRenderPass = new VkRenderPass(this, depthView != null);
        return this.activeRenderPass;
    }

    @Override
    public void submitRenderPass() {
        if (this.activeRenderPass != null) {
            this.activeRenderPass.finish();
            this.activeRenderPass = null;
        }
        Renderer.getInstance().endRenderPass();
        this.inRenderPass = false;
        this.resetBufferCache();
    }

    @Override
    public void clearColorTexture(GpuTexture colorAttachment, Vector4fc clearColorVec) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        int clearColor = ARGB.colorFromFloat(clearColorVec.w(), clearColorVec.x(), clearColorVec.y(), clearColorVec.z());
        if (Renderer.isRecording()) {
            if (Renderer.getInstance().getMainPass().getColorAttachment() == colorAttachment) {
                Renderer.getInstance().getMainPass().rebindMainTarget();
                VRenderSystem.setClearColor(clearColorVec.x(), clearColorVec.y(), clearColorVec.z(), clearColorVec.w());
                Renderer.clearAttachments(0x4000);
            } else {
                VkGpuTexture vkGpuTexture = (VkGpuTexture) colorAttachment;
                VkGlFramebuffer.bindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
                VkGlFramebuffer.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, vkGpuTexture.glId(), 0);
                VkGlFramebuffer.beginRendering(VkGlFramebuffer.getFramebuffer(framebufferId));
                VRenderSystem.setClearColor(clearColorVec.x(), clearColorVec.y(), clearColorVec.z(), clearColorVec.w());
                Renderer.clearAttachments(0x4000);
                Renderer.getInstance().endRenderPass();

                vkGpuTexture.setClearColor(clearColor);
                Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
                if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == vkGpuTexture.getVulkanImage()) {
                    vkGpuTexture.getFbo(null).clearAttachments();
                }
            }
        } else {
            GraphicsQueue graphicsQueue = DeviceManager.getGraphicsQueue();
            var commandBuffer = graphicsQueue.getCommandBuffer();
            VkGpuTexture vkGpuTexture = (VkGpuTexture) colorAttachment;

            VkGlFramebuffer glFramebuffer = VkGlFramebuffer.getFramebuffer(this.framebufferId);
            glFramebuffer.setAttachmentTexture(GL30.GL_COLOR_ATTACHMENT0, vkGpuTexture.glId());
            glFramebuffer.create();

            Framebuffer framebuffer = glFramebuffer.getFramebuffer();
            var renderPass = glFramebuffer.getRenderPass();
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer.handle, renderPass, stack);
            }

            VRenderSystem.setClearColor(clearColorVec.x(), clearColorVec.y(), clearColorVec.z(), clearColorVec.w());
            Renderer.clearAttachments(commandBuffer.handle, 0x4000, 0, 0, framebuffer.getWidth(), framebuffer.getHeight());
            renderPass.endRenderPass(commandBuffer.handle);

            long fence = graphicsQueue.submitCommands(commandBuffer);
            Synchronization.waitFence(fence);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorAttachment, Vector4fc clearColorVec, GpuTexture depthAttachment, double clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        int clearColor = ARGB.colorFromFloat(clearColorVec.w(), clearColorVec.x(), clearColorVec.y(), clearColorVec.z());
        if (Renderer.getInstance().getMainPass().getColorAttachment() == colorAttachment) {
            Renderer.getInstance().getMainPass().rebindMainTarget();
            VRenderSystem.clearDepth(clearDepth);
            VRenderSystem.setClearColor(clearColorVec.x(), clearColorVec.y(), clearColorVec.z(), clearColorVec.w());
            Renderer.clearAttachments(0x4100);
        } else {
            VkGpuTexture vkColor = (VkGpuTexture) colorAttachment;
            VkGpuTexture vkDepth = (VkGpuTexture) depthAttachment;
            VkFbo fbo = vkColor.getFbo(depthAttachment);
            vkColor.setClearColor(clearColor);
            vkDepth.setDepthClearValue((float) clearDepth);

            Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
            if (boundFramebuffer != null
                    && boundFramebuffer.getColorAttachment() == vkColor.getVulkanImage()
                    && boundFramebuffer.getDepthAttachment() == vkDepth.getVulkanImage()) {
                fbo.clearAttachments();
            }
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorAttachment, Vector4fc clearColorVec, GpuTexture depthAttachment, double clearDepth,
                                           int x0, int y0, int width, int height, int mipLevel) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        VRenderSystem.clearDepth(clearDepth);
        VRenderSystem.setClearColor(clearColorVec.x(), clearColorVec.y(), clearColorVec.z(), clearColorVec.w());

        int framebufferHeight = colorAttachment.getHeight(mipLevel);
        int adjustedY = framebufferHeight - height - y0;

        VkGpuTexture vkColor = (VkGpuTexture) colorAttachment;
        VkGpuTexture vkDepth = (VkGpuTexture) depthAttachment;
        Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
        if (boundFramebuffer != null
                && boundFramebuffer.getColorAttachment() == vkColor.getVulkanImage()
                && boundFramebuffer.getDepthAttachment() == vkDepth.getVulkanImage()) {
            Renderer.clearAttachments(0x4100, x0, adjustedY, width, height);
        } else {
            vkColor.getFbo(depthAttachment).bind();
            Renderer.clearAttachments(0x4100, x0, adjustedY, width, height);
        }
    }

    @Override
    public void clearDepthTexture(GpuTexture depthAttachment, double clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        VkGpuTexture vkDepth = (VkGpuTexture) depthAttachment;
        Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
        if (boundFramebuffer != null && boundFramebuffer.getDepthAttachment() == vkDepth.getVulkanImage()) {
            VRenderSystem.clearDepth(clearDepth);
            Renderer.clearAttachments(0x100);
        } else {
            vkDepth.setDepthClearValue((float) clearDepth);
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();
        if (vkGpuBuffer.closed) {
            throw new IllegalStateException("Buffer already closed");
        }

        int size = byteBuffer.remaining();
        long dstOffset = gpuBufferSlice.offset();
        long dstId = vkGpuBuffer.buffer.getId();

        var commandBuffer = Renderer.getInstance().getTransferCb();
        trackTransferCb(commandBuffer);

        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
        stagingBuffer.copyBuffer(size, byteBuffer);
        long srcOffset = stagingBuffer.getOffset();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!commandBuffer.isRecording()) {
                commandBuffer.begin(stack);
            }

            if (!this.dirtyTransferBuffers.add(dstId)) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
                barrier.get(0)
                        .sType$Default()
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(dstId)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .offset(0)
                        .size(VK_WHOLE_SIZE);

                vkCmdPipelineBarrier(commandBuffer.handle,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, barrier, null);
            }

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.size(size);
            copyRegion.srcOffset(srcOffset);
            copyRegion.dstOffset(dstOffset);

            vkCmdCopyBuffer(commandBuffer.handle, stagingBuffer.getId(), dstId, copyRegion);
        }
    }

    @Override
    public void copyToBuffer(GpuBufferSlice srcSlice, GpuBufferSlice dstSlice) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        VkGpuBuffer srcBuf = (VkGpuBuffer) srcSlice.buffer();
        VkGpuBuffer dstBuf = (VkGpuBuffer) dstSlice.buffer();
        long srcId = srcBuf.buffer.getId();
        long dstId = dstBuf.buffer.getId();

        var commandBuffer = Renderer.getInstance().getTransferCb();
        trackTransferCb(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!commandBuffer.isRecording()) {
                commandBuffer.begin(stack);
            }

            if (this.dirtyTransferBuffers.contains(srcId)) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
                barrier.get(0)
                        .sType$Default()
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(srcId)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .offset(srcSlice.offset())
                        .size(srcSlice.length());
                vkCmdPipelineBarrier(commandBuffer.handle,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, barrier, null);
            }

            if (!this.dirtyTransferBuffers.add(dstId)) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
                barrier.get(0)
                        .sType$Default()
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(dstId)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .offset(dstSlice.offset())
                        .size(dstSlice.length());
                vkCmdPipelineBarrier(commandBuffer.handle,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, barrier, null);
            }

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(srcSlice.offset());
            copyRegion.dstOffset(dstSlice.offset());
            copyRegion.size(srcSlice.length());

            vkCmdCopyBuffer(commandBuffer.handle, srcId, dstId, copyRegion);
        }
    }

    @Override
    public void writeToTexture(GpuTexture gpuTexture, ByteBuffer byteBuffer, int mipLevel, int arrayLayer,
                               int xOffset, int yOffset, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        VkGpuTexture vkTexture = (VkGpuTexture) gpuTexture;
        VulkanImage image = vkTexture.getVulkanImage();

        VTextureSelector.setActiveTexture(0);
        VTextureSelector.bindTexture(image);

        if (byteBuffer.isDirect()) {
            long ptr = MemoryUtil.memAddress(byteBuffer);
            VTextureSelector.uploadSubTexture(mipLevel, arrayLayer, width, height, xOffset, yOffset, 0, 0, width, ptr);
        } else {
            ByteBuffer direct = MemoryUtil.memAlloc(byteBuffer.remaining());
            try {
                ByteBuffer dup = byteBuffer.slice();
                direct.put(dup).flip();
                VTextureSelector.uploadSubTexture(mipLevel, arrayLayer, width, height, xOffset, yOffset, 0, 0, width, MemoryUtil.memAddress(direct));
            } finally {
                MemoryUtil.memFree(direct);
            }
        }
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice src, int srcX, int srcY, int srcRowWidth, int srcHeight,
                                    GpuTexture dstTexture, int dstX, int dstY, int width, int height, int mipLevel, int arrayLayer) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        VkGpuBuffer vkBuffer = (VkGpuBuffer) src.buffer();
        VkGpuTexture vkTexture = (VkGpuTexture) dstTexture;
        VulkanImage image = vkTexture.getVulkanImage();

        long ptr = vkBuffer.getBuffer().getDataPtr();
        int blockSize = dstTexture.getFormat().blockSize();
        long sliceOffset = src.offset() + ((long) srcY * srcRowWidth + srcX) * blockSize;

        VTextureSelector.setActiveTexture(0);
        VTextureSelector.bindTexture(image);

        if (ptr != 0L) {
            VTextureSelector.uploadSubTexture(mipLevel, arrayLayer, width, height, dstX, dstY, srcY, srcX, srcRowWidth, ptr + src.offset());
        } else {
            int bytes = width * height * blockSize;
            ByteBuffer temp = MemoryUtil.memAlloc(bytes);
            try {
                long copyOffset = sliceOffset;
                try (GpuBufferSlice.MappedView mapped = src.buffer().map(copyOffset, bytes, true, false)) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(mapped.data()), MemoryUtil.memAddress(temp), bytes);
                }
                VTextureSelector.uploadSubTexture(mipLevel, arrayLayer, width, height, dstX, dstY, 0, 0, width, MemoryUtil.memAddress(temp));
            } finally {
                MemoryUtil.memFree(temp);
            }
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long offset, Runnable callback, int mipLevel) {
        this.copyTextureToBuffer(gpuTexture, gpuBuffer, offset, callback, mipLevel, 0, 0, gpuTexture.getWidth(mipLevel), gpuTexture.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long dstOffset, Runnable callback,
                                    int mipLevel, int xOffset, int yOffset, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBuffer;
        VkGpuTexture vkGpuTexture = (VkGpuTexture) gpuTexture;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            GraphicsQueue graphicsQueue = DeviceManager.getGraphicsQueue();
            CommandPool.CommandBuffer commandBuffer = graphicsQueue.beginCommands();
            VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();
            VulkanImage image = vkGpuTexture.getVulkanImage();
            Buffer buffer = vkGpuBuffer.getBuffer();

            int prevLayout = image.getCurrentLayout();
            image.transitionImageLayout(stack, vkCommandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            ImageUtil.copyImageToBuffer(stack, vkCommandBuffer, buffer.getId(), image.getId(), mipLevel, width, height, xOffset, yOffset, dstOffset, width, height);

            if (prevLayout != VK_IMAGE_LAYOUT_UNDEFINED && prevLayout != VK_IMAGE_LAYOUT_PREINITIALIZED) {
                image.transitionImageLayout(stack, vkCommandBuffer, prevLayout);
            } else {
                image.transitionImageLayout(stack, vkCommandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            if (Renderer.isRecording()) {
                graphicsQueue.submitCommands(commandBuffer, true);
                Synchronization.INSTANCE.addCommandBuffer(commandBuffer, true);
                MemoryManager.getInstance().addFrameOp(callback);
            } else {
                long fence = graphicsQueue.submitCommands(commandBuffer, false);
                Synchronization.waitFence(fence);
                commandBuffer.reset();
                callback.run();
            }
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture srcTexture, GpuTexture dstTexture, int mipLevel,
                                     int dstX, int dstY, int srcX, int srcY, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        VulkanImage srcImg = ((VkGpuTexture) srcTexture).getVulkanImage();
        VulkanImage dstImg = ((VkGpuTexture) dstTexture).getVulkanImage();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            GraphicsQueue graphicsQueue = DeviceManager.getGraphicsQueue();
            CommandPool.CommandBuffer commandBuffer = graphicsQueue.beginCommands();
            VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();

            int prevSrcLayout = srcImg.getCurrentLayout();
            int prevDstLayout = dstImg.getCurrentLayout();

            srcImg.transitionImageLayout(stack, vkCommandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstImg.transitionImageLayout(stack, vkCommandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
            copyRegion.srcSubresource().aspectMask(srcImg.aspect).mipLevel(mipLevel).baseArrayLayer(0).layerCount(1);
            copyRegion.srcOffset().set(srcX, srcY, 0);
            copyRegion.dstSubresource().aspectMask(dstImg.aspect).mipLevel(mipLevel).baseArrayLayer(0).layerCount(1);
            copyRegion.dstOffset().set(dstX, dstY, 0);
            copyRegion.extent().set(width, height, 1);

            vkCmdCopyImage(vkCommandBuffer,
                    srcImg.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImg.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copyRegion);

            int restoreSrc = (prevSrcLayout != VK_IMAGE_LAYOUT_UNDEFINED && prevSrcLayout != VK_IMAGE_LAYOUT_PREINITIALIZED)
                    ? prevSrcLayout : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            int restoreDst = (prevDstLayout != VK_IMAGE_LAYOUT_UNDEFINED && prevDstLayout != VK_IMAGE_LAYOUT_PREINITIALIZED)
                    ? prevDstLayout : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            srcImg.transitionImageLayout(stack, vkCommandBuffer, restoreSrc);
            dstImg.transitionImageLayout(stack, vkCommandBuffer, restoreDst);

            if (Renderer.isRecording()) {
                graphicsQueue.submitCommands(commandBuffer, true);
                Synchronization.INSTANCE.addCommandBuffer(commandBuffer, true);
            } else {
                long fence = graphicsQueue.submitCommands(commandBuffer, false);
                Synchronization.waitFence(fence);
                commandBuffer.reset();
            }
        }
    }

    @Override
    public GpuFence createFence() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
        return new VkGpuFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool queryPool, int index) {
        if (queryPool instanceof VkQueryPool vkPool) {
            vkPool.recordTimestamp(index);
        }
    }

    protected void executeDraw(VkRenderPass renderPass, int vertexOffset, int firstIndex, int count,
                               @Nullable IndexType indexType, int instanceCount, int firstInstance) {
        if (!this.trySetup(renderPass)) {
            return;
        }
        if (instanceCount < 1) {
            instanceCount = 1;
        }
        if (vertexOffset < 0) {
            vertexOffset = 0;
        }

        VkCommandBuffer vkCommandBuffer = Renderer.getCommandBuffer();
        bindVertexBuffers(renderPass, vkCommandBuffer);

        if (indexType != null && renderPass.indexBuffer != null) {
            VkGpuBuffer indexBuffer = (VkGpuBuffer) renderPass.indexBuffer;
            int vkIndexType = switch (indexType) {
                case SHORT -> VK_INDEX_TYPE_UINT16;
                case INT -> VK_INDEX_TYPE_UINT32;
            };
            long ibId = indexBuffer.buffer.getId();
            long ibOffset = indexBuffer.offset;
            if (ibId != this.boundIndexBufferId || ibOffset != this.boundIndexOffset || vkIndexType != this.boundIndexType) {
                vkCmdBindIndexBuffer(vkCommandBuffer, ibId, ibOffset, vkIndexType);
                this.boundIndexBufferId = ibId;
                this.boundIndexOffset = ibOffset;
                this.boundIndexType = vkIndexType;
            }
            vkCmdDrawIndexed(vkCommandBuffer, count, instanceCount, firstIndex, vertexOffset, firstInstance);
        } else {
            var autoIndexBuffer = Renderer.getDrawer().getAutoIndexBuffer(renderPass.pipeline.primitiveTopology(), count);
            if (autoIndexBuffer != null) {
                int indexCount = autoIndexBuffer.getIndexCount(count);
                autoIndexBuffer.checkCapacity(count);
                long ibId = autoIndexBuffer.getIndexBuffer().getId();
                int vkIndexType = autoIndexBuffer.getIndexBuffer().indexType.value;
                if (ibId != this.boundIndexBufferId || 0L != this.boundIndexOffset || vkIndexType != this.boundIndexType) {
                    vkCmdBindIndexBuffer(vkCommandBuffer, ibId, 0L, vkIndexType);
                    this.boundIndexBufferId = ibId;
                    this.boundIndexOffset = 0L;
                    this.boundIndexType = vkIndexType;
                }
                vkCmdDrawIndexed(vkCommandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
            } else {
                vkCmdDraw(vkCommandBuffer, count, instanceCount, vertexOffset, firstInstance);
            }
        }
    }

    protected void executeDrawIndexedIndirect(VkRenderPass renderPass, GpuBufferSlice indirectBuffer, int drawCount) {
        if (!this.trySetup(renderPass) || renderPass.indexBuffer == null) {
            return;
        }
        VkCommandBuffer vkCommandBuffer = Renderer.getCommandBuffer();
        bindVertexBuffers(renderPass, vkCommandBuffer);

        VkGpuBuffer indexBuffer = (VkGpuBuffer) renderPass.indexBuffer;
        int vkIndexType = renderPass.indexType == IndexType.SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
        long ibId = indexBuffer.buffer.getId();
        long ibOffset = indexBuffer.offset;
        if (ibId != this.boundIndexBufferId || ibOffset != this.boundIndexOffset || vkIndexType != this.boundIndexType) {
            vkCmdBindIndexBuffer(vkCommandBuffer, ibId, ibOffset, vkIndexType);
            this.boundIndexBufferId = ibId;
            this.boundIndexOffset = ibOffset;
            this.boundIndexType = vkIndexType;
        }

        VkGpuBuffer indirect = (VkGpuBuffer) indirectBuffer.buffer();
        vkCmdDrawIndexedIndirect(vkCommandBuffer, indirect.buffer.getId(), indirectBuffer.offset(), drawCount, VkDrawIndexedIndirectCommand.SIZEOF);
    }

    protected void executeDrawIndirect(VkRenderPass renderPass, GpuBufferSlice indirectBuffer, int drawCount) {
        if (!this.trySetup(renderPass)) {
            return;
        }
        VkCommandBuffer vkCommandBuffer = Renderer.getCommandBuffer();
        bindVertexBuffers(renderPass, vkCommandBuffer);

        VkGpuBuffer indirect = (VkGpuBuffer) indirectBuffer.buffer();
        vkCmdDrawIndirect(vkCommandBuffer, indirect.buffer.getId(), indirectBuffer.offset(), drawCount, VkDrawIndirectCommand.SIZEOF);
    }

    private void bindVertexBuffers(VkRenderPass renderPass, VkCommandBuffer vkCommandBuffer) {
        int currentEpoch = Renderer.getInstance().getBufferBindEpoch();
        if (vkCommandBuffer != this.lastCmdBuffer || currentEpoch != this.lastBufferBindEpoch) {
            this.lastCmdBuffer = vkCommandBuffer;
            this.lastBufferBindEpoch = currentEpoch;
            this.resetBufferCache();
        }

        for (int slot = 0; slot <= renderPass.maxVertexBufferSlot; slot++) {
            GpuBufferSlice slice = renderPass.vertexBuffers[slot];
            if (slice == null) {
                continue;
            }
            VkGpuBuffer vb = (VkGpuBuffer) slice.buffer();
            long vbId = vb.buffer.getId();
            long vbOffset = vb.offset + slice.offset();
            if (vbId != this.boundVertexBufferIds[slot] || vbOffset != this.boundVertexOffsets[slot]) {
                MemoryUtil.memPutLong(P_VERTEX_BUFFERS, vbId);
                MemoryUtil.memPutLong(P_VERTEX_OFFSETS, vbOffset);
                nvkCmdBindVertexBuffers(vkCommandBuffer, slot, 1, P_VERTEX_BUFFERS, P_VERTEX_OFFSETS);
                this.boundVertexBufferIds[slot] = vbId;
                this.boundVertexOffsets[slot] = vbOffset;
            }
        }
    }

    public boolean trySetup(VkRenderPass renderPass) {
        if (renderPass.pipeline == null) {
            return false;
        }

        applyPipelineState(renderPass.pipeline);
        setupUniforms(renderPass);

        if (renderPass.isScissorEnabled()) {
            Renderer.setScissor(
                    renderPass.getScissorX(), renderPass.getScissorY(), renderPass.getScissorWidth(), renderPass.getScissorHeight()
            );
        } else {
            Renderer.resetScissor();
        }

        boolean bound = bindPipeline(renderPass.pipeline);
        if (bound && renderPass.pendingPushConstants != null && renderPass.pipeline.pushConstantsSize() > 0) {
            vkCmdPushConstants(
                    Renderer.getCommandBuffer(),
                    renderPass.pipeline.graphicsPipeline().getLayout(),
                    VK_SHADER_STAGE_ALL_GRAPHICS,
                    0,
                    renderPass.pendingPushConstants
            );
        }
        return bound;
    }

    public void setupUniforms(VkRenderPass renderPass) {
        Pipeline pipeline = renderPass.pipeline.graphicsPipeline();

        if (renderPass.uniformsDirty) {
            for (UBO ubo : pipeline.getBuffers()) {
                Object val = renderPass.uniforms.get(ubo.binding);
                if (val instanceof GpuBufferSlice gpuBufferSlice) {
                    VkGpuBuffer gpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();
                    ubo.setUseGlobalBuffer(false);
                    ubo.getBufferSlice().set(gpuBuffer.buffer, gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
                } else {
                    ubo.setUseGlobalBuffer(true);
                    ubo.setUpdate(true);
                }
            }
            renderPass.uniformsDirty = false;
        }

        if (renderPass.samplersDirty) {
            for (ImageDescriptor imageDescriptor : pipeline.getImageDescriptors()) {
                Object val = renderPass.uniforms.get(imageDescriptor.getBinding());
                if (val instanceof TextureViewAndSampler tvs
                        && tvs.view() instanceof VkTextureView textureView
                        && tvs.sampler() instanceof VkSampler sampler) {
                    VkGpuTexture gpuTexture = textureView.texture();
                    if (gpuTexture.isClosed()) {
                        continue;
                    }
                    VulkanImage vulkanImage = gpuTexture.getVulkanImage();
                    vulkanImage.setSampler(sampler.getId());
                    VTextureSelector.bindTexture(imageDescriptor.imageIdx, vulkanImage);
                }
            }
            renderPass.samplersDirty = false;
        }
    }

    public boolean bindPipeline(VkBackendRenderPipeline backendPipeline) {
        GraphicsPipeline pipeline = backendPipeline.graphicsPipeline();
        if (pipeline == null) {
            return false;
        }

        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        return true;
    }

    public void applyPipelineState(VkBackendRenderPipeline backendPipeline) {
        if (this.lastPipeline != backendPipeline) {
            this.lastPipeline = backendPipeline;

            DepthStencilState dss = backendPipeline.depthStencilState();
            if (dss != null && dss.depthTest() != CompareOp.ALWAYS_PASS) {
                VRenderSystem.depthTest = true;
                VRenderSystem.depthFun = VkConst.toVkDepthOp(dss.depthTest());
            } else {
                VRenderSystem.depthTest = false;
            }

            VRenderSystem.cullMode = backendPipeline.cull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE;

            var colorTargets = backendPipeline.colorTargetStates();
            ColorTargetState cts = (colorTargets == null || colorTargets.isEmpty()) ? ColorTargetState.DEFAULT : colorTargets.get(0);
            if (cts != null && cts.blendFunction().isPresent()) {
                BlendFunction blendFunction = cts.blendFunction().get();
                PipelineState.blendInfo.enabled = true;
                PipelineState.blendInfo.setBlendFuncSeparate(
                        VkConst.toVkBlendFactor(blendFunction.color().sourceFactor()),
                        VkConst.toVkBlendFactor(blendFunction.color().destFactor()),
                        VkConst.toVkBlendFactor(blendFunction.alpha().sourceFactor()),
                        VkConst.toVkBlendFactor(blendFunction.alpha().destFactor())
                );
            } else {
                PipelineState.blendInfo.enabled = false;
            }

            VRenderSystem.polygonMode = backendPipeline.polygonMode() == PolygonMode.WIREFRAME
                    ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL;
            VRenderSystem.depthMask = dss != null && dss.writeDepth();
            if (cts != null) {
                VRenderSystem.colorMask = PipelineState.ColorMask.getColorMask(
                        cts.writeRed(), cts.writeGreen(),
                        cts.writeBlue(), cts.writeAlpha());
            } else {
                VRenderSystem.colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);
            }

            float biasConst = dss != null ? dss.depthBiasConstant() : 0.0F;
            float biasSlope = dss != null ? dss.depthBiasScaleFactor() : 0.0F;
            if (biasConst == 0.0F && biasSlope == 0.0F) {
                VRenderSystem.disablePolygonOffset();
            } else {
                VRenderSystem.polygonOffset(biasSlope, biasConst);
                VRenderSystem.enablePolygonOffset();
            }

            VRenderSystem.disableColorLogicOp();
            VRenderSystem.topology = VkConst.toVkTopology(backendPipeline.primitiveTopology());
        }
    }

    protected VkGpuDevice getDevice() {
        return this.device;
    }
}
