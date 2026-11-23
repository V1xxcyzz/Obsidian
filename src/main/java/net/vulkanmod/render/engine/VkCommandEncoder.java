package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
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
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VkCommandEncoder implements CommandEncoderBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final VkGpuDevice device;

    private static final java.nio.LongBuffer VERTEX_BUFFERS = MemoryUtil.memAllocLong(1);
    private static final java.nio.LongBuffer VERTEX_OFFSETS = MemoryUtil.memAllocLong(1);
    private static final long P_VERTEX_BUFFERS = MemoryUtil.memAddress0(VERTEX_BUFFERS);
    private static final long P_VERTEX_OFFSETS = MemoryUtil.memAddress0(VERTEX_OFFSETS);

    @Nullable
    private RenderPipeline lastPipeline;
    private boolean inRenderPass;

    @Nullable
    private EGlProgram lastProgram;

    private int framebufferId = VkGlFramebuffer.genFramebufferId();

    // Cached Vulkan command buffer bindings to eliminate redundant binds across draws
    private VkCommandBuffer lastCmdBuffer;
    private int lastBufferBindEpoch = -1;
    private long boundVertexBufferId = 0L;
    private long boundVertexOffset = -1L;
    private long boundIndexBufferId = 0L;
    private long boundIndexOffset = -1L;
    private int boundIndexType = -1;

    // Tracks buffers written in the active transfer command buffer to avoid unnecessary barriers
    private CommandPool.CommandBuffer trackedTransferCb;
    private final LongOpenHashSet dirtyTransferBuffers = new LongOpenHashSet();

    protected VkCommandEncoder(VkGpuDevice glDevice) {
        this.device = glDevice;
    }

    public void invalidateCachedState() {
        this.lastPipeline = null;
        this.lastCmdBuffer = null;
        this.lastBufferBindEpoch = -1;
        this.boundVertexBufferId = 0L;
        this.boundVertexOffset = -1L;
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
    public boolean isInRenderPass() {
        return this.inRenderPass;
    }

    @Override
    public RenderPassBackend createRenderPass(Supplier<String> supplier, GpuTextureView colorAttachmentView, OptionalInt optionalInt) {
        return this.createRenderPass(supplier, colorAttachmentView, optionalInt, null, OptionalDouble.empty());
    }

    @Override
    public RenderPassBackend createRenderPass(Supplier<String> supplier, GpuTextureView colorAttachmentView, OptionalInt optionalInt, @Nullable GpuTextureView depthTexture, OptionalDouble optionalDouble) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        } else {
            if (optionalDouble.isPresent() && depthTexture == null) {
                LOGGER.warn("Depth clear value was provided but no depth texture is being used");
            }

            if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachmentView.texture()) {
                this.boundVertexBufferId = 0L;
                this.boundVertexOffset = -1L;
                this.boundIndexBufferId = 0L;
                this.boundIndexOffset = -1L;
                this.boundIndexType = -1;
                Renderer.getInstance().getMainPass().rebindMainTarget();

                int j = 0;
                if (optionalInt.isPresent()) {
                    int k = optionalInt.getAsInt();
                    GL11.glClearColor(ARGB.redFloat(k), ARGB.greenFloat(k), ARGB.blueFloat(k), ARGB.alphaFloat(k));
                    j |= 16384;
                }

                if (depthTexture != null && optionalDouble.isPresent()) {
                    GL11.glClearDepth(optionalDouble.getAsDouble());
                    j |= 256;
                }

                if (j != 0) {
                    GlStateManager._disableScissorTest();
                    GlStateManager._depthMask(true);
                    GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
                    GlStateManager._clear(j);
                }

                return new VkRenderPass(this, depthTexture != null, true);
            }

            if (colorAttachmentView.isClosed()) {
                throw new IllegalStateException("Color texture is closed");
            } else if (depthTexture != null && depthTexture.isClosed()) {
                throw new IllegalStateException("Depth texture is closed");
            } else {
                this.inRenderPass = true;
                GpuTexture depthTexture1 = depthTexture != null ? depthTexture.texture() : null;
                VkFbo fbo = ((VkTextureView)colorAttachmentView).getFbo(depthTexture1);
                fbo.bind();

                int j = 0;
                if (optionalInt.isPresent()) {
                    int k = optionalInt.getAsInt();
                    GL11.glClearColor(ARGB.redFloat(k), ARGB.greenFloat(k), ARGB.blueFloat(k), ARGB.alphaFloat(k));
                    j |= 16384;
                }

                if (depthTexture != null && optionalDouble.isPresent()) {
                    GL11.glClearDepth(optionalDouble.getAsDouble());
                    j |= 256;
                }

                if (j != 0) {
                    GlStateManager._disableScissorTest();
                    GlStateManager._depthMask(true);
                    GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
                    GlStateManager._clear(j);
                }

                GlStateManager._viewport(0, 0, colorAttachmentView.getWidth(0), colorAttachmentView.getHeight(0));
                this.lastPipeline = null;
                return new VkRenderPass(this, depthTexture != null, true);
            }
        }

    }

    @Override
    public void clearColorTexture(GpuTexture colorAttachment, int clearColor) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        else if (Renderer.isRecording()) {
            if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachment) {
                Renderer.getInstance().getMainPass().rebindMainTarget();

                VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                Renderer.clearAttachments(0x4000);
            }
            else {
                VkGpuTexture vkGpuTexture = (VkGpuTexture) colorAttachment;
                VkGlFramebuffer.bindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
                VkGlFramebuffer.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, vkGpuTexture.glId(), 0);

                VkGlFramebuffer.beginRendering(VkGlFramebuffer.getFramebuffer(framebufferId));
                VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                Renderer.clearAttachments(0x4000);
                Renderer.getInstance().endRenderPass();

                VkFbo fbo = ((VkGpuTexture)colorAttachment).getFbo(null);

                ((VkGpuTexture) colorAttachment).setClearColor(clearColor);

                Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
                if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture) colorAttachment).getVulkanImage()) {
                    fbo.clearAttachments();
                }
            }
        }
        else {
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

            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
            Renderer.clearAttachments(commandBuffer.handle, 0x4000, 0, 0, framebuffer.getWidth(), framebuffer.getHeight());
            renderPass.endRenderPass(commandBuffer.handle);

            long fence = graphicsQueue.submitCommands(commandBuffer);
            Synchronization.waitFence(fence);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorAttachment, int clearColor, GpuTexture depthAttachment, double clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        else {
            if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachment) {
                Renderer.getInstance().getMainPass().rebindMainTarget();

                VRenderSystem.clearDepth(clearDepth);
                VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                Renderer.clearAttachments(0x4100);
            }
            else {
                VkFbo fbo = ((VkGpuTexture)colorAttachment).getFbo(depthAttachment);

                ((VkGpuTexture) colorAttachment).setClearColor(clearColor);
                ((VkGpuTexture) depthAttachment).setDepthClearValue((float) clearDepth);

                Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
                if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture) colorAttachment).getVulkanImage()
                    && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture) depthAttachment).getVulkanImage())
                {
                    fbo.clearAttachments();
                }
            }
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorAttachment, int clearColor, GpuTexture depthAttachment, double clearDepth, int x0, int y0, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        } else {
            VRenderSystem.clearDepth(clearDepth);
            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));

            int framebufferHeight = colorAttachment.getHeight(0);
            y0 = framebufferHeight - height - y0;

            Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
            if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture) colorAttachment).getVulkanImage()
                && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture) depthAttachment).getVulkanImage())
            {
                Renderer.clearAttachments(0x4100, x0, y0, width, height);
            }
            else {
                VkGpuTexture gpuTexture = (VkGpuTexture) colorAttachment;
                gpuTexture.getFbo(depthAttachment).bind();

                Renderer.clearAttachments(0x4100, x0, y0, width, height);
            }
        }
    }

    @Override
    public void clearDepthTexture(GpuTexture depthAttachment, double clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        else {
            Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
            if (boundFramebuffer != null && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture) depthAttachment).getVulkanImage()) {
                VRenderSystem.clearDepth(clearDepth);
                Renderer.clearAttachments(0x100);
            }
            else {
                ((VkGpuTexture) depthAttachment).setDepthClearValue((float) clearDepth);
            }
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();
            if (vkGpuBuffer.closed) {
                throw new IllegalStateException("Buffer already closed");
            }
            else {
                int size = byteBuffer.remaining();
                if (size + gpuBufferSlice.offset() > vkGpuBuffer.size()) {
                    throw new IllegalArgumentException(
                            "Cannot write more data than this buffer can hold (attempting to write " + size + " bytes at offset " + gpuBufferSlice.offset() + " to " + gpuBufferSlice.length() + " slice size)"
                    );
                } else {
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

                        // Only emit a targeted buffer barrier when this same buffer was already written in this transfer batch
                        if (!this.dirtyTransferBuffers.add(dstId)) {
                            VkBufferMemoryBarrier.Buffer bufferMemoryBarriers = VkBufferMemoryBarrier.calloc(1, stack);
                            VkBufferMemoryBarrier bufferMemoryBarrier = bufferMemoryBarriers.get(0);
                            bufferMemoryBarrier.sType$Default();
                            bufferMemoryBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                            bufferMemoryBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                            bufferMemoryBarrier.buffer(dstId);
                            bufferMemoryBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                            bufferMemoryBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                            bufferMemoryBarrier.offset(0);
                            bufferMemoryBarrier.size(VK_WHOLE_SIZE);

                            vkCmdPipelineBarrier(commandBuffer.handle,
                                                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                 0,
                                                 null,
                                                 bufferMemoryBarriers,
                                                 null);
                        }

                        VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
                        copyRegion.size(size);
                        copyRegion.srcOffset(srcOffset);
                        copyRegion.dstOffset(dstOffset);

                        vkCmdCopyBuffer(commandBuffer.handle, stagingBuffer.getId(), dstId, copyRegion);
                    }
                }
            }
        }
    }

    public GpuBuffer.MappedView mapBuffer(GpuBuffer gpuBuffer, boolean readable, boolean writable) {
        return this.mapBuffer(gpuBuffer.slice(), readable, writable);
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice gpuBufferSlice, boolean readable, boolean writable) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            VkGpuBuffer gpuBuffer = (VkGpuBuffer)(gpuBufferSlice.buffer());
            if (gpuBuffer.closed) {
                throw new IllegalStateException("Buffer already closed");
            } else if (!readable && !writable) {
                throw new IllegalArgumentException("At least read or write must be true");
            } else if (readable && (gpuBuffer.usage() & 1) == 0) {
                throw new IllegalStateException("Buffer is not readable");
            } else if (writable && (gpuBuffer.usage() & 2) == 0) {
                throw new IllegalStateException("Buffer is not writable");
            } else if (gpuBufferSlice.offset() + gpuBufferSlice.length() > gpuBuffer.size()) {
                throw new IllegalArgumentException(
                        "Cannot map more data than this buffer can hold (attempting to map "
                        + gpuBufferSlice.length()
                        + " bytes at offset "
                        + gpuBufferSlice.offset()
                        + " from "
                        + gpuBuffer.size()
                        + " size buffer)"
                );
            } else {
                int i = 0;
                if (readable) {
                    i |= 1;
                }

                if (writable) {
                    i |= 34;
                }

                ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(gpuBuffer.getBuffer().getDataPtr() + gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
                return new VkGpuBuffer.MappedView(0, byteBuffer);
            }
        }
    }

    public void copyToBuffer(GpuBufferSlice gpuBufferSlice, GpuBufferSlice gpuBufferSlice2) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();
            if (vkGpuBuffer.closed) {
                throw new IllegalStateException("Source buffer already closed");
            } else if ((vkGpuBuffer.usage() & 8) == 0) {
                throw new IllegalStateException("Source buffer needs USAGE_COPY_DST to be a destination for a copy");
            } else {
                VkGpuBuffer vkGpuBuffer2 = (VkGpuBuffer) gpuBufferSlice2.buffer();
                if (vkGpuBuffer2.closed) {
                    throw new IllegalStateException("Target buffer already closed");
                } else if ((vkGpuBuffer2.usage() & 8) == 0) {
                    throw new IllegalStateException("Target buffer needs USAGE_COPY_DST to be a destination for a copy");
                } else if (gpuBufferSlice.length() != gpuBufferSlice2.length()) {
                    long var6 = gpuBufferSlice.length();
                    throw new IllegalArgumentException("Cannot copy from slice of size " + var6 + " to slice of size " + gpuBufferSlice2.length() + ", they must be equal");
                } else if (gpuBufferSlice.offset() + gpuBufferSlice.length() > vkGpuBuffer.size()) {
                    long var5 = gpuBufferSlice.length();
                    throw new IllegalArgumentException("Cannot copy more data than the source buffer holds (attempting to copy " + var5 + " bytes at offset " + gpuBufferSlice.offset() + " from " + vkGpuBuffer.size() + " size buffer)");
                } else if (gpuBufferSlice2.offset() + gpuBufferSlice2.length() > vkGpuBuffer2.size()) {
                    long var10002 = gpuBufferSlice2.length();
                    throw new IllegalArgumentException("Cannot copy more data than the target buffer can hold (attempting to copy " + var10002 + " bytes at offset " + gpuBufferSlice2.offset() + " to " + vkGpuBuffer2.size() + " size buffer)");
                } else {
                    long srcId = vkGpuBuffer.buffer.getId();
                    long dstId = vkGpuBuffer2.buffer.getId();

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
                                   .offset(gpuBufferSlice.offset())
                                   .size(gpuBufferSlice.length());

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
                                   .offset(gpuBufferSlice2.offset())
                                   .size(gpuBufferSlice2.length());

                            vkCmdPipelineBarrier(commandBuffer.handle,
                                                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                 0, null, barrier, null);
                        }

                        VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
                        copyRegion.srcOffset(gpuBufferSlice.offset());
                        copyRegion.dstOffset(gpuBufferSlice2.offset());
                        copyRegion.size(gpuBufferSlice.length());

                        vkCmdCopyBuffer(commandBuffer.handle, srcId, dstId, copyRegion);
                    }
                }
            }
        }
    }

    public void writeToTexture(GpuTexture gpuTexture, NativeImage nativeImage) {
        int i = gpuTexture.getWidth(0);
        int j = gpuTexture.getHeight(0);
        if (nativeImage.getWidth() != i || nativeImage.getHeight() != j) {
            throw new IllegalArgumentException(
                    "Cannot replace texture of size " + i + "x" + j + " with image of size " + nativeImage.getWidth() + "x" + nativeImage.getHeight()
            );
        } else if (gpuTexture.isClosed()) {
            throw new IllegalStateException("Destination texture is closed");
        } else {
            this.writeToTexture(gpuTexture, nativeImage, 0, 0, 0, 0, i, j, 0, 0);
        }
    }

    @Override
    public void writeToTexture(GpuTexture gpuTexture, NativeImage nativeImage, int level, int arrayLayer, int xOffset, int yOffset, int width, int height, int unpackSkipPixels, int unpackSkipRows) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (level >= 0 && level < gpuTexture.getMipLevels()) {
            if (unpackSkipPixels + width > nativeImage.getWidth() || unpackSkipRows + height > nativeImage.getHeight()) {
                throw new IllegalArgumentException(
                        "Copy source ("
                        + nativeImage.getWidth()
                        + "x"
                        + nativeImage.getHeight()
                        + ") is not large enough to read a rectangle of "
                        + width
                        + "x"
                        + height
                        + " from "
                        + unpackSkipPixels
                        + "x"
                        + unpackSkipRows
                );
            } else if (xOffset + width > gpuTexture.getWidth(level) || yOffset + height > gpuTexture.getHeight(level)) {
                throw new IllegalArgumentException(
                        "Dest texture (" + width + "x" + height + ") is not large enough to write a rectangle of " + width + "x" + height + " at " + xOffset + "x" + yOffset + " (at mip level " + level + ")"
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Destination texture is closed");
            } else {
                VTextureSelector.setActiveTexture(0);
                var glTexture = VkGlTexture.getTexture(((GlTexture) gpuTexture).glId());
//                VTextureSelector.bindTexture(((VkGpuTexture) gpuTexture).getVulkanImage());
                VTextureSelector.bindTexture(glTexture.getVulkanImage());
                VTextureSelector.uploadSubTexture(level, arrayLayer, width, height, xOffset, yOffset, unpackSkipRows, unpackSkipPixels, nativeImage.getWidth(), nativeImage.getPointer());
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel " + level + ", must be >= 0 and < " + gpuTexture.getMipLevels());
        }
    }

    @Override
    public void writeToTexture(GpuTexture gpuTexture, ByteBuffer byteBuffer, NativeImage.Format format, int level, int j, int xOffset, int yOffset, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (level >= 0 && level < gpuTexture.getMipLevels()) {
            if (width * height * format.components() > byteBuffer.remaining()) {
                throw new IllegalArgumentException(
                        "Copy would overrun the source buffer (remaining length of " + byteBuffer.remaining() + ", but copy is " + width + "x" + height + " of format " + format + ")"
                );
            } else if (xOffset + width > gpuTexture.getWidth(level) || yOffset + height > gpuTexture.getHeight(level)) {
                throw new IllegalArgumentException(
                        "Dest texture ("
                        + gpuTexture.getWidth(level)
                        + "x"
                        + gpuTexture.getHeight(level)
                        + ") is not large enough to write a rectangle of "
                        + width
                        + "x"
                        + height
                        + " at "
                        + xOffset
                        + "x"
                        + yOffset
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Destination texture is closed");
            } else if ((gpuTexture.usage() & 1) == 0) {
                throw new IllegalStateException("Color texture must have USAGE_COPY_DST to be a destination for a write");
            } else if (j >= gpuTexture.getDepthOrLayers()) {
                throw new UnsupportedOperationException("Depth or layer is out of range, must be >= 0 and < " + gpuTexture.getDepthOrLayers());
            }
            else {
                GlStateManager._bindTexture(((VkGpuTexture)gpuTexture).id);

                GlStateManager._pixelStore(3314, width);
                GlStateManager._pixelStore(3316, 0);
                GlStateManager._pixelStore(3315, 0);
                GlStateManager._pixelStore(3317, format.components());
                GlStateManager._texSubImage2D(3553, level, xOffset, yOffset, width, height, GlConst.toGl(format), 5121, byteBuffer);
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel, must be >= 0 and < " + gpuTexture.getMipLevels());
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long i, Runnable runnable, int j) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            this.copyTextureToBuffer(gpuTexture, gpuBuffer, i, runnable, j, 0, 0, gpuTexture.getWidth(j), gpuTexture.getHeight(j));
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long dstOffset, Runnable runnable, int mipLevel, int xOffset, int yOffset, int width, int height) {
        VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBuffer;
        VkGpuTexture vkGpuTexture = (VkGpuTexture) gpuTexture;

        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (mipLevel >= 0 && mipLevel < gpuTexture.getMipLevels()) {
            if (gpuTexture.getWidth(mipLevel) * gpuTexture.getHeight(mipLevel) * vkGpuTexture.getVulkanImage().formatSize + dstOffset > gpuBuffer.size()) {
                throw new IllegalArgumentException(
                        "Buffer of size "
                        + gpuBuffer.size()
                        + " is not large enough to hold "
                        + width
                        + "x"
                        + height
                        + " pixels ("
                        + vkGpuTexture.getVulkanImage().formatSize
                        + " bytes each) starting from offset "
                        + dstOffset
                );
            }
            else if (xOffset + width > gpuTexture.getWidth(mipLevel) || yOffset + height > gpuTexture.getHeight(mipLevel)) {
                throw new IllegalArgumentException(
                        "Copy source texture ("
                        + gpuTexture.getWidth(mipLevel)
                        + "x"
                        + gpuTexture.getHeight(mipLevel)
                        + ") is not large enough to read a rectangle of "
                        + width
                        + "x"
                        + height
                        + " from "
                        + xOffset
                        + ","
                        + yOffset
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Source texture is closed");
            } else if (gpuBuffer.isClosed()) {
                throw new IllegalStateException("Destination buffer is closed");
            } else {
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
                        MemoryManager.getInstance().addFrameOp(runnable);
                    } else {
                        long fence = graphicsQueue.submitCommands(commandBuffer, false);
                        Synchronization.waitFence(fence);
                        commandBuffer.reset();
                        runnable.run();
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel " + mipLevel + ", must be >= 0 and < " + gpuTexture.getMipLevels());
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture gpuTexture, GpuTexture gpuTexture2, int mipLevel, int j, int k, int l, int m, int n, int o) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (mipLevel >= 0 && mipLevel < gpuTexture.getMipLevels() && mipLevel < gpuTexture2.getMipLevels()) {
            if (j + n > gpuTexture2.getWidth(mipLevel) || k + o > gpuTexture2.getHeight(mipLevel)) {
                throw new IllegalArgumentException(
                        "Dest texture ("
                        + gpuTexture2.getWidth(mipLevel)
                        + "x"
                        + gpuTexture2.getHeight(mipLevel)
                        + ") is not large enough to write a rectangle of "
                        + n
                        + "x"
                        + o
                        + " at "
                        + j
                        + "x"
                        + k
                );
            } else if (l + n > gpuTexture.getWidth(mipLevel) || m + o > gpuTexture.getHeight(mipLevel)) {
                throw new IllegalArgumentException(
                        "Source texture ("
                        + gpuTexture.getWidth(mipLevel)
                        + "x"
                        + gpuTexture.getHeight(mipLevel)
                        + ") is not large enough to read a rectangle of "
                        + n
                        + "x"
                        + o
                        + " at "
                        + l
                        + "x"
                        + m
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Source texture is closed");
            } else if (gpuTexture2.isClosed()) {
                throw new IllegalStateException("Destination texture is closed");
            } else {
                VkGpuTexture srcTex = (VkGpuTexture) gpuTexture;
                VkGpuTexture dstTex = (VkGpuTexture) gpuTexture2;
                VulkanImage srcImg = srcTex.getVulkanImage();
                VulkanImage dstImg = dstTex.getVulkanImage();

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GraphicsQueue graphicsQueue = DeviceManager.getGraphicsQueue();
                    CommandPool.CommandBuffer commandBuffer = graphicsQueue.beginCommands();
                    VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();

                    int prevSrcLayout = srcImg.getCurrentLayout();
                    int prevDstLayout = dstImg.getCurrentLayout();

                    srcImg.transitionImageLayout(stack, vkCommandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                    dstImg.transitionImageLayout(stack, vkCommandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                    VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
                    copyRegion.srcSubresource()
                              .aspectMask(srcImg.aspect)
                              .mipLevel(mipLevel)
                              .baseArrayLayer(0)
                              .layerCount(1);
                    copyRegion.srcOffset().set(l, m, 0);
                    copyRegion.dstSubresource()
                              .aspectMask(dstImg.aspect)
                              .mipLevel(mipLevel)
                              .baseArrayLayer(0)
                              .layerCount(1);
                    copyRegion.dstOffset().set(j, k, 0);
                    copyRegion.extent().set(n, o, 1);

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
        } else {
            throw new IllegalArgumentException("Invalid mipLevel " + mipLevel + ", must be >= 0 and < " + gpuTexture.getMipLevels() + " and < " + gpuTexture2.getMipLevels());
        }
    }

    @Override
    public GpuFence createFence() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            return new VkGpuFence();
        }
    }

    @Override
    public GpuQuery timerQueryBegin() {
        return null;
    }

    @Override
    public void timerQueryEnd(GpuQuery gpuQuery) {

    }

    @Override
    public void presentTexture(GpuTextureView gpuTexture) {
        throw new UnsupportedOperationException();
    }

    protected <T> void executeDrawMultiple(
            VkRenderPass renderPass,
            Collection<RenderPass.Draw<T>> collection,
            @Nullable GpuBuffer indexBuffer,
            @Nullable VertexFormat.IndexType indexType,
            Collection<String> collection2,
            T object
    ) {
        if (this.trySetup(renderPass)) {
            if (indexType == null) {
                indexType = VertexFormat.IndexType.SHORT;
            }

            Pipeline pipeline = ExtendedRenderPipeline.of(renderPass.getPipeline()).getPipeline();

            for (RenderPass.Draw draw : collection) {
                VertexFormat.IndexType indexType2 = draw.indexType() == null ? indexType : draw.indexType();
                renderPass.setIndexBuffer(draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer(), indexType2);
                renderPass.setVertexBuffer(draw.slot(), draw.vertexBuffer());

                if (VkRenderPass.VALIDATION) {
                    if (renderPass.indexBuffer == null) {
                        throw new IllegalStateException("Missing index buffer");
                    }

                    if (renderPass.indexBuffer.isClosed()) {
                        throw new IllegalStateException("Index buffer has been closed!");
                    }

                    if (renderPass.vertexBuffers[0] == null) {
                        throw new IllegalStateException("Missing vertex buffer at slot 0");
                    }

                    if (renderPass.vertexBuffers[0].isClosed()) {
                        throw new IllegalStateException("Vertex buffer at slot 0 has been closed!");
                    }
                }

                BiConsumer<T, RenderPass.UniformUploader> biConsumer = draw.uniformUploaderConsumer();
                if (biConsumer != null) {
                    biConsumer.accept(object, (string, gpuBufferSlice) -> {
                        EGlProgram glProgram = ExtendedRenderPipeline.of(renderPass.pipeline).getProgram();
                        if (glProgram.getUniform(string) instanceof Uniform.Ubo ubo1) {

                            int blockBinding;
                            try {
                                blockBinding = ubo1.blockBinding();
                            } catch (Throwable var7) {
                                throw new MatchException(var7.toString(), var7);
                            }

                            VkGpuBuffer gpuBuffer1 = (VkGpuBuffer) gpuBufferSlice.buffer();

                            UBO ubo = pipeline.getUBO(blockBinding);
                            ubo.setUseGlobalBuffer(false);
                            ubo.getBufferSlice().set(gpuBuffer1.buffer, gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
                        }
                    });

                    Renderer.getInstance().uploadAndBindUBOs(pipeline);
                }

                this.drawFromBuffers(renderPass, 0, draw.firstIndex(), draw.indexCount(), indexType2, renderPass.pipeline, 1);
            }
        }
    }

    protected void executeDraw(VkRenderPass renderPass, int vertexOffset, int firstIndex, int vertexCount, @Nullable VertexFormat.IndexType indexType, int instanceCount) {
        if (this.trySetup(renderPass)) {
            if (VkRenderPass.VALIDATION) {
                if (indexType != null) {
                    if (renderPass.indexBuffer == null) {
                        throw new IllegalStateException("Missing index buffer");
                    }

                    if (renderPass.indexBuffer.isClosed()) {
                        throw new IllegalStateException("Index buffer has been closed!");
                    }
                }

                if (renderPass.vertexBuffers[0] == null) {
                    throw new IllegalStateException("Missing vertex buffer at slot 0");
                }

                if (renderPass.vertexBuffers[0].isClosed()) {
                    throw new IllegalStateException("Vertex buffer at slot 0 has been closed!");
                }
            }

            this.drawFromBuffers(renderPass, vertexOffset, firstIndex, vertexCount, indexType, renderPass.pipeline, instanceCount);
        }
    }

    public void drawFromBuffers(VkRenderPass renderPass, int vertexOffset, int firstIndex, int vertexCount,
                                @Nullable VertexFormat.IndexType indexType, RenderPipeline renderPipeline, int instanceCount)
    {
        if (instanceCount < 1) {
            instanceCount = 1;
        }
        if (vertexOffset < 0) {
            vertexOffset = 0;
        }

        VkCommandBuffer vkCommandBuffer = Renderer.getCommandBuffer();
        int currentEpoch = Renderer.getInstance().getBufferBindEpoch();
        if (vkCommandBuffer != this.lastCmdBuffer || currentEpoch != this.lastBufferBindEpoch) {
            this.lastCmdBuffer = vkCommandBuffer;
            this.lastBufferBindEpoch = currentEpoch;
            this.boundVertexBufferId = 0L;
            this.boundVertexOffset = -1L;
            this.boundIndexBufferId = 0L;
            this.boundIndexOffset = -1L;
            this.boundIndexType = -1;
        }

        VkGpuBuffer vertexBuffer = (VkGpuBuffer)renderPass.vertexBuffers[0];
        if (vertexBuffer != null) {
            long vbId = vertexBuffer.buffer.getId();
            long vbOffset = vertexBuffer.offset;
            if (vbId != this.boundVertexBufferId || vbOffset != this.boundVertexOffset) {
                MemoryUtil.memPutLong(P_VERTEX_BUFFERS, vbId);
                MemoryUtil.memPutLong(P_VERTEX_OFFSETS, vbOffset);
                nvkCmdBindVertexBuffers(vkCommandBuffer, 0, 1, P_VERTEX_BUFFERS, P_VERTEX_OFFSETS);
                this.boundVertexBufferId = vbId;
                this.boundVertexOffset = vbOffset;
            }
        }

        if (renderPass.indexBuffer != null) {
            VkGpuBuffer indexBuffer = (VkGpuBuffer)renderPass.indexBuffer;

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
            vkCmdDrawIndexed(vkCommandBuffer, vertexCount, instanceCount, firstIndex, vertexOffset, 0);
        }
        else {
            var autoIndexBuffer = Renderer.getDrawer().getAutoIndexBuffer(renderPipeline.getVertexFormatMode(), vertexCount);
            if (autoIndexBuffer != null) {
                int indexCount = autoIndexBuffer.getIndexCount(vertexCount);
                long ibId = autoIndexBuffer.getIndexBuffer().getId();
                int vkIndexType = autoIndexBuffer.getIndexBuffer().indexType.value;
                if (ibId != this.boundIndexBufferId || 0L != this.boundIndexOffset || vkIndexType != this.boundIndexType) {
                    vkCmdBindIndexBuffer(vkCommandBuffer, ibId, 0L, vkIndexType);
                    this.boundIndexBufferId = ibId;
                    this.boundIndexOffset = 0L;
                    this.boundIndexType = vkIndexType;
                }
                vkCmdDrawIndexed(vkCommandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, 0);
            }
            else {
                vkCmdDraw(vkCommandBuffer, vertexCount, instanceCount, vertexOffset, 0);
            }
        }
    }

    public boolean trySetup(VkRenderPass renderPass) {
        if (VkRenderPass.VALIDATION) {
            if (renderPass.pipeline == null) {
                throw new IllegalStateException("Can't draw without a render pipeline");
            }

            for (RenderPipeline.UniformDescription uniformDescription : renderPass.pipeline.getUniforms()) {
                Object object = renderPass.uniforms.get(uniformDescription.name());
                if (object == null && !GlProgram.BUILT_IN_UNIFORMS.contains(uniformDescription.name())) {
                    throw new IllegalStateException("Missing uniform " + uniformDescription.name() + " (should be " + uniformDescription.type() + ")");
                }
            }

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

        return bindPipeline(renderPass.pipeline);
    }

    public void setupUniforms(VkRenderPass renderPass) {
        Pipeline pipeline = ExtendedRenderPipeline.of(renderPass.pipeline).getPipeline();

        if (renderPass.uniformsDirty) {
            for (UBO ubo : pipeline.getBuffers()) {
                String uniformName = ubo.name;
                GpuBufferSlice gpuBufferSlice = renderPass.uniforms.get(uniformName);

                // In case uniform buffer is not set, fallback to global buffer
                if (gpuBufferSlice == null) {
                    ubo.setUseGlobalBuffer(true);
                    ubo.setUpdate(true);
                    continue;
                }

                VkGpuBuffer gpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();

                ubo.setUseGlobalBuffer(false);
                ubo.getBufferSlice().set(gpuBuffer.buffer, gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
            }
            renderPass.uniformsDirty = false;
        }

        if (renderPass.samplersDirty) {
            for (ImageDescriptor imageDescriptor : pipeline.getImageDescriptors()) {
                String uniformName = imageDescriptor.name;
                int samplerIndex = imageDescriptor.imageIdx;

                var textureSampler = renderPass.samplers.get(uniformName);
                if (textureSampler == null) {
                    continue;
                }

                VkTextureView textureView = textureSampler.view();
                VkGpuTexture gpuTexture = textureView.texture();
                if (gpuTexture.isClosed()) {
                    continue;
                }

                VulkanImage vulkanImage = gpuTexture.getVulkanImage();
                vulkanImage.setSampler(textureSampler.sampler().getId());
                VTextureSelector.bindTexture(samplerIndex, vulkanImage);
            }
            renderPass.samplersDirty = false;
        }
        renderPass.dirtyUniforms.clear();
    }

    public boolean bindPipeline(RenderPipeline renderPipeline) {
        Pipeline pipeline = ExtendedRenderPipeline.of(renderPipeline).getPipeline();

        if (pipeline == null) {
            return false;
        }

        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline((GraphicsPipeline) pipeline);
//        VTextureSelector.bindShaderTextures(pipeline);

        renderer.uploadAndBindUBOs(pipeline);

        return true;
    }

    public void applyPipelineState(RenderPipeline renderPipeline) {
        if (this.lastPipeline != renderPipeline) {
            this.lastPipeline = renderPipeline;

            DepthStencilState dss = renderPipeline.getDepthStencilState();
            CompareOp depthFunc = dss.depthTest();
            if (depthFunc != CompareOp.ALWAYS_PASS) {
                VRenderSystem.depthTest = true;
                VRenderSystem.depthFun = toVkDepthOp(depthFunc);
            } else {
                VRenderSystem.depthTest = false;
            }

            VRenderSystem.cullMode = renderPipeline.isCull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE;

            ColorTargetState cts = renderPipeline.getColorTargetState();
            if (cts.blendFunction().isPresent()) {
                BlendFunction blendFunction = cts.blendFunction().get();
                PipelineState.blendInfo.enabled = true;
                PipelineState.blendInfo.setBlendFuncSeparate(
                        toVkBlendFactor(blendFunction.sourceColor()),
                        toVkBlendFactor(blendFunction.destColor()),
                        toVkBlendFactor(blendFunction.sourceAlpha()),
                        toVkBlendFactor(blendFunction.destAlpha())
                );
            } else {
                PipelineState.blendInfo.enabled = false;
            }

            VRenderSystem.polygonMode = renderPipeline.getPolygonMode() == PolygonMode.WIREFRAME
                    ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL;
            VRenderSystem.depthMask = dss.writeDepth();
            VRenderSystem.colorMask = PipelineState.ColorMask.getColorMask(
                    cts.writeRed(), cts.writeGreen(),
                    cts.writeBlue(), cts.writeAlpha());

            float biasConst = dss.depthBiasConstant();
            float biasSlope = dss.depthBiasScaleFactor();
            if (biasConst == 0.0F && biasSlope == 0.0F) {
                VRenderSystem.disablePolygonOffset();
            } else {
                VRenderSystem.polygonOffset(biasSlope, biasConst);
                VRenderSystem.enablePolygonOffset();
            }

            VRenderSystem.disableColorLogicOp();

            VRenderSystem.topology = toVkTopology(renderPipeline.getVertexFormatMode());
        }
    }

    private static int toVkDepthOp(CompareOp fn) {
        return switch (fn) {
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case LESS_THAN_OR_EQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case LESS_THAN -> VK_COMPARE_OP_LESS;
            case GREATER_THAN -> VK_COMPARE_OP_GREATER;
            case GREATER_THAN_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case NOT_EQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case NEVER_PASS -> VK_COMPARE_OP_NEVER;
            case ALWAYS_PASS -> VK_COMPARE_OP_ALWAYS;
        };
    }

    private static int toVkBlendFactor(SourceFactor f) {
        return switch (f) {
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ZERO -> VK_BLEND_FACTOR_ZERO;
        };
    }

    private static int toVkBlendFactor(DestFactor f) {
        return switch (f) {
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ZERO -> VK_BLEND_FACTOR_ZERO;
        };
    }

    private static int toVkTopology(VertexFormat.Mode mode) {
        return switch (mode) {
            case LINES, DEBUG_LINES, DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case QUADS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }

    public void finishRenderPass(boolean forceEnd) {
        if (forceEnd) {
            Renderer.getInstance().endRenderPass();
        }

        this.inRenderPass = false;
        this.boundVertexBufferId = 0L;
        this.boundVertexOffset = -1L;
        this.boundIndexBufferId = 0L;
        this.boundIndexOffset = -1L;
        this.boundIndexType = -1;
    }

    protected VkGpuDevice getDevice() {
        return this.device;
    }
}
