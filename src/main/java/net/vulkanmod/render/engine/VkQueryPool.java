package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.commands.GpuQueryPool;

import java.util.Arrays;
import java.util.OptionalLong;

public class VkQueryPool implements GpuQueryPool {
    private final int size;
    private final long[] timestamps;
    private final boolean[] valid;
    private boolean closed;

    public VkQueryPool(int size) {
        this.size = size;
        this.timestamps = new long[size];
        this.valid = new boolean[size];
    }

    public void recordTimestamp(int index) {
        if (index >= 0 && index < this.size) {
            this.timestamps[index] = System.nanoTime();
            this.valid[index] = true;
        }
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public OptionalLong getValue(int index) {
        if (index >= 0 && index < this.size && this.valid[index]) {
            return OptionalLong.of(this.timestamps[index]);
        }
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong[] getValues(int first, int count) {
        OptionalLong[] result = new OptionalLong[count];
        for (int i = 0; i < count; i++) {
            result[i] = this.getValue(first + i);
        }
        return result;
    }

    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
        Arrays.fill(this.valid, false);
    }
}
