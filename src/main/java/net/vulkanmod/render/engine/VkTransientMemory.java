package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class VkTransientMemory implements TransientMemory {
    private static final long DEFAULT_BLOCK_SIZE = 4L * 1024L * 1024L;
    private static final int ALL_TRANSIENT_USAGE =
            GpuBuffer.USAGE_MAP_WRITE
            | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_COPY_DST
            | GpuBuffer.USAGE_VERTEX
            | GpuBuffer.USAGE_INDEX
            | GpuBuffer.USAGE_UNIFORM
            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
            | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    private final List<CpuBlock> cpuBlocks = new ArrayList<>();
    private final List<GpuBlock> gpuBlocks = new ArrayList<>();
    private final List<CpuBlock> retiredCpuBlocks = new ArrayList<>();
    private final List<GpuBlock> retiredGpuBlocks = new ArrayList<>();
    private int currentFrame = 0;

    private static final class CpuBlock {
        final long address;
        final long capacity;
        long used;
        int frame;

        CpuBlock(long capacity, int frame) {
            this.address = MemoryUtil.nmemAlignedAlloc(64, capacity);
            this.capacity = capacity;
            this.frame = frame;
        }

        void free() {
            MemoryUtil.nmemAlignedFree(this.address);
        }
    }

    private static final class GpuBlock {
        final VkGpuBuffer buffer;
        final long capacity;
        long used;
        int frame;

        GpuBlock(VkDebugLabel debugLabel, long capacity, int frame) {
            this.buffer = new VkGpuBuffer(debugLabel, () -> "TransientGpuBlock", ALL_TRANSIENT_USAGE, capacity);
            this.capacity = capacity;
            this.frame = frame;
        }
    }

    public void beginFrame(int frame) {
        this.currentFrame = frame;
        for (CpuBlock b : this.cpuBlocks) {
            this.retiredCpuBlocks.add(b);
        }
        this.cpuBlocks.clear();
        this.retiredCpuBlocks.removeIf(b -> {
            if (b.frame == frame) {
                b.free();
                return true;
            }
            return false;
        });

        for (GpuBlock b : this.gpuBlocks) {
            this.retiredGpuBlocks.add(b);
        }
        this.gpuBlocks.clear();
        this.retiredGpuBlocks.removeIf(b -> {
            if (b.frame == frame) {
                b.buffer.close();
                return true;
            }
            return false;
        });
    }

    public void close() {
        for (CpuBlock b : this.cpuBlocks) b.free();
        for (CpuBlock b : this.retiredCpuBlocks) b.free();
        for (GpuBlock b : this.gpuBlocks) b.buffer.close();
        for (GpuBlock b : this.retiredGpuBlocks) b.buffer.close();
        this.cpuBlocks.clear();
        this.retiredCpuBlocks.clear();
        this.gpuBlocks.clear();
        this.retiredGpuBlocks.clear();
    }

    private static long alignUp(long value, long alignment) {
        long align = Math.max(1L, alignment);
        return (value + align - 1L) & ~(align - 1L);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long align, long zeroOffset, long zeroLen) {
        long effectiveAlign = Math.max(16L, align);
        CpuBlock target = null;
        long startOffset = 0L;

        if (!this.cpuBlocks.isEmpty()) {
            CpuBlock last = this.cpuBlocks.get(this.cpuBlocks.size() - 1);
            long aligned = alignUp(last.used, effectiveAlign);
            if (aligned + size <= last.capacity) {
                target = last;
                startOffset = aligned;
            }
        }

        if (target == null) {
            long cap = Math.max(DEFAULT_BLOCK_SIZE, alignUp(size, effectiveAlign));
            target = new CpuBlock(cap, this.currentFrame);
            this.cpuBlocks.add(target);
            startOffset = 0L;
        }

        target.used = startOffset + size;
        if (zeroLen > 0) {
            MemoryUtil.memSet(target.address + startOffset + zeroOffset, 0, zeroLen);
        }
        return MemoryUtil.memByteBuffer(target.address + startOffset, (int) size);
    }

    private GpuBufferSlice allocateGpuSlice(long size, long align, long zeroOffset, long zeroLen) {
        long effectiveAlign = Math.max(256L, align);
        GpuBlock target = null;
        long startOffset = 0L;

        if (!this.gpuBlocks.isEmpty()) {
            GpuBlock last = this.gpuBlocks.get(this.gpuBlocks.size() - 1);
            long aligned = alignUp(last.used, effectiveAlign);
            if (aligned + size <= last.capacity) {
                target = last;
                startOffset = aligned;
            }
        }

        if (target == null) {
            long cap = Math.max(DEFAULT_BLOCK_SIZE, alignUp(size, effectiveAlign));
            target = new GpuBlock(VkGpuDevice.getInstance().debugLabels(), cap, this.currentFrame);
            this.gpuBlocks.add(target);
            startOffset = 0L;
        }

        target.used = startOffset + size;
        long dataPtr = target.buffer.getBuffer().getDataPtr();
        if (zeroLen > 0 && dataPtr != 0L) {
            MemoryUtil.memSet(dataPtr + startOffset + zeroOffset, 0, zeroLen);
        }
        return target.buffer.slice(startOffset, size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long align, int usage, long zeroOffset, long zeroLen) {
        GpuBufferSlice slice = this.allocateGpuSlice(size, align, zeroOffset, zeroLen);
        return slice.map(false, true);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long align, int usage, long zeroOffset, long zeroLen) {
        return this.allocateGpuSlice(size, align, zeroOffset, zeroLen);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long align, int usage, long zeroOffset, long zeroLen) {
        GpuBufferSlice slice = this.allocateGpuSlice(size, align, zeroOffset, zeroLen);
        return slice.map(false, true);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long align, int usage, long zeroOffset, long zeroLen) {
        return this.uploadAll(data, align, zeroOffset, zeroLen);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long align, int usage, long zeroOffset, long zeroLen) {
        return this.uploadAll(data, align, zeroOffset, zeroLen);
    }

    private GpuBufferSlice uploadAll(List<ByteBuffer> data, long align, long zeroOffset, long zeroLen) {
        long total = 0L;
        for (ByteBuffer buf : data) {
            total += buf.remaining();
        }
        if (total == 0L) {
            return this.allocateGpuSlice(Math.max(1L, zeroOffset + zeroLen), align, zeroOffset, zeroLen);
        }
        long allocSize = Math.max(total, zeroOffset + zeroLen);
        GpuBufferSlice slice = this.allocateGpuSlice(allocSize, align, zeroOffset, zeroLen);
        try (GpuBufferSlice.MappedView mapped = slice.map(false, true)) {
            ByteBuffer dst = mapped.data();
            int pos = 0;
            for (ByteBuffer src : data) {
                int len = src.remaining();
                if (len > 0) {
                    long srcAddr = MemoryUtil.memAddress(src);
                    long dstAddr = MemoryUtil.memAddress(dst, pos);
                    MemoryUtil.memCopy(srcAddr, dstAddr, len);
                    pos += len;
                }
            }
        }
        return slice;
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long align, int usage) {
        return this.multiUpload(data, align);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long align, int usage) {
        return this.multiUpload(data, align);
    }

    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, long align) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer src : data) {
            int len = src.remaining();
            GpuBufferSlice slice = this.allocateGpuSlice(Math.max(1, len), align, 0L, 0L);
            if (len > 0) {
                try (GpuBufferSlice.MappedView mapped = slice.map(false, true)) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(src), MemoryUtil.memAddress(mapped.data()), len);
                }
            }
            slices.add(slice);
        }
        return slices;
    }
}
