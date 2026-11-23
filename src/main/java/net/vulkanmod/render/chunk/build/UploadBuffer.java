package net.vulkanmod.render.chunk.build;

import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.BufferUtil;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.TerrainBuilder;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class UploadBuffer {
    public final int indexCount;
    public final boolean autoIndices;
    public final boolean indexOnly;
    private final ByteBuffer packedVertexBuffer;
    private final int[] facingSizes;
    private final ByteBuffer indexBuffer;

    public UploadBuffer(TerrainBuilder terrainBuilder, TerrainBuilder.DrawState drawState) {
        this.indexCount = drawState.indexCount();
        this.autoIndices = drawState.sequentialIndex();
        this.indexOnly = drawState.indexOnly();

        if (!this.indexOnly) {
            this.facingSizes = new int[QuadFacing.COUNT];
            int totalBytes = 0;

            for (int i = 0; i < QuadFacing.COUNT; i++) {
                var bufferBuilder = terrainBuilder.getBufferBuilder(i);
                if (bufferBuilder.getVertices() > 0) {
                    int bytes = bufferBuilder.getNextElementByte();
                    this.facingSizes[i] = bytes;
                    totalBytes += bytes;
                }
            }

            if (totalBytes > 0) {
                ByteBuffer packed = MemoryUtil.memAlloc(totalBytes);
                long dstPtr = MemoryUtil.memAddress0(packed);
                long cursor = dstPtr;
                for (int i = 0; i < QuadFacing.COUNT; i++) {
                    int bytes = this.facingSizes[i];
                    if (bytes > 0) {
                        long srcPtr = terrainBuilder.getBufferBuilder(i).getPtr();
                        MemoryUtil.memCopy(srcPtr, cursor, bytes);
                        cursor += bytes;
                    }
                }
                this.packedVertexBuffer = packed;
            } else {
                this.packedVertexBuffer = null;
            }
        }
        else {
            this.facingSizes = null;
            this.packedVertexBuffer = null;
        }

        if (!drawState.sequentialIndex()) {
            this.indexBuffer = BufferUtil.clone(terrainBuilder.getIndexBuffer());
        }
        else {
            this.indexBuffer = null;
        }
    }

    public int indexCount() {
        return indexCount;
    }

    public ByteBuffer getPackedVertexBuffer() {
        return packedVertexBuffer;
    }

    public int getFacingSize(int facing) {
        return facingSizes != null ? facingSizes[facing] : 0;
    }

    public ByteBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public void release() {
        if (packedVertexBuffer != null) {
            MemoryUtil.memFree(packedVertexBuffer);
        }

        if (indexBuffer != null)
            MemoryUtil.memFree(indexBuffer);
    }
}
