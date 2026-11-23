package net.vulkanmod.render.chunk.buffer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.queue.TransferQueue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class UploadManager {
    public static UploadManager INSTANCE;

    public static void createInstance() {
        INSTANCE = new UploadManager();
    }

    Queue queue = DeviceManager.getTransferQueue();
    CommandPool.CommandBuffer commandBuffer;

    // Tracks buffers written via recordUpload (need TRANSFER_WRITE -> TRANSFER_READ if copied from)
    LongOpenHashSet dirtyUploadBuffers = new LongOpenHashSet();
    // Tracks buffers written via full-buffer copyBuffer (need TRANSFER_WRITE -> TRANSFER_WRITE if subsequently uploaded to)
    LongOpenHashSet copiedDstBuffers = new LongOpenHashSet();

    public void submitUploads() {
        submitUploads(Initializer.CONFIG.asyncChunkUploads);
    }

    public void submitUploads(boolean useSemaphore) {
        if (this.commandBuffer == null)
            return;

        this.queue.submitCommands(this.commandBuffer, useSemaphore);

        Synchronization.INSTANCE.addCommandBuffer(this.commandBuffer, useSemaphore);

        this.commandBuffer = null;
        this.dirtyUploadBuffers.clear();
        this.copiedDstBuffers.clear();
    }

    public void recordUpload(Buffer buffer, long dstOffset, long bufferSize, ByteBuffer src) {
        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
        stagingBuffer.copyBuffer((int) bufferSize, src);

        beginCommands();
        VkCommandBuffer commandBuffer = this.commandBuffer.getHandle();
        long dstId = buffer.getId();

        // Disjoint segment uploads into the same AreaBuffer do not overlap and need no barrier.
        // Only insert a targeted buffer barrier if this buffer was previously the destination of a copyBuffer().
        if (this.copiedDstBuffers.remove(dstId)) {
            emitBufferBarrier(commandBuffer, dstId, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        }

        this.dirtyUploadBuffers.add(dstId);

        TransferQueue.uploadBufferCmd(commandBuffer, stagingBuffer.getId(), stagingBuffer.getOffset(), dstId, dstOffset, bufferSize);
    }

    public void copyBuffer(Buffer src, Buffer dst) {
        copyBuffer(src, 0, dst, 0, src.getBufferSize());
    }

    public void copyBuffer(Buffer src, long srcOffset, Buffer dst, long dstOffset, long size) {
        beginCommands();

        VkCommandBuffer commandBuffer = this.commandBuffer.getHandle();
        long srcId = src.getId();
        long dstId = dst.getId();

        if (this.dirtyUploadBuffers.remove(srcId) || this.copiedDstBuffers.remove(srcId)) {
            emitBufferBarrier(commandBuffer, srcId, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        }

        this.copiedDstBuffers.add(dstId);

        TransferQueue.uploadBufferCmd(commandBuffer, srcId, srcOffset, dstId, dstOffset, size);
    }

    private static void emitBufferBarrier(VkCommandBuffer commandBuffer, long bufferId, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer bufferMemoryBarriers = VkBufferMemoryBarrier.calloc(1, stack);
            VkBufferMemoryBarrier barrier = bufferMemoryBarriers.get(0);
            barrier.sType$Default();
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.buffer(bufferId);
            barrier.srcAccessMask(srcAccess);
            barrier.dstAccessMask(dstAccess);
            barrier.offset(0);
            barrier.size(VK_WHOLE_SIZE);

            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    bufferMemoryBarriers,
                    null);
        }
    }

    public void syncUploads() {
        submitUploads(false);

        Synchronization.INSTANCE.waitFences();
    }

    private void beginCommands() {
        if (this.commandBuffer == null)
            this.commandBuffer = queue.beginCommands();
    }

}
