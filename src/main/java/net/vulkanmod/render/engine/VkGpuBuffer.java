package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;

@Environment(EnvType.CLIENT)
public class VkGpuBuffer extends BaseGpuBuffer {
    protected boolean closed;
    @Nullable protected final Supplier<String> label;

    Buffer buffer;
    int offset;

    protected VkGpuBuffer(VkDebugLabel debugLabel, @Nullable Supplier<String> supplier, int usage, long size) {
        super(usage, size);
        this.label = supplier;

        int vkUsage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) {
            vkUsage |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        }

        boolean mappable = (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE)) != 0;
        MemoryType memoryType = mappable ? MemoryTypes.HOST_MEM : MemoryTypes.GPU_MEM;

        String labelStr = supplier != null ? supplier.get() : null;
        this.buffer = new Buffer(labelStr, vkUsage, memoryType);
        this.buffer.createBuffer(this.size());
    }

    protected VkGpuBuffer(Buffer buffer) {
        super(buffer.usage, buffer.getBufferSize());
        this.label = null;
        this.buffer = buffer;
    }

    public static VkGpuBuffer from(Buffer buffer) {
        return new VkGpuBuffer(buffer);
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        this.checkCanBeUsed();
        if (offset < 0 || length < 0 || offset + length > this.size()) {
            throw new IllegalArgumentException("Map range [" + offset + ", " + (offset + length) + ") out of bounds for buffer size " + this.size());
        }

        GpuBufferSlice slice = this.slice(offset, length);
        long ptr = this.buffer.getDataPtr();
        if (ptr != 0L) {
            ByteBuffer view = MemoryUtil.memByteBuffer(ptr + offset, (int) length);
            return new GpuBufferSlice.MappedView(slice, view, () -> {});
        }

        ByteBuffer staging = MemoryUtil.memAlloc((int) length);
        return new GpuBufferSlice.MappedView(slice, staging, () -> {
            try {
                if (write) {
                    staging.position(0);
                    staging.limit((int) length);
                    this.buffer.copyBuffer(staging, (int) length, (int) offset);
                }
            } finally {
                MemoryUtil.memFree(staging);
            }
        });
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            MemoryManager.getInstance().addToFreeable(this.buffer);
        }
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }
}
