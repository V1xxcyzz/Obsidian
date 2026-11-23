package net.vulkanmod.render.vertex;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.vulkanmod.render.util.SortUtil;
import org.lwjgl.system.MemoryUtil;

public class QuadSorter {

    private float[] sortingPoints;
    private int pointCount;
    private float sortX = Float.NaN;
    private float sortY = Float.NaN;
    private float sortZ = Float.NaN;
    private boolean indexOnly;

    private VertexFormat format;
    private int vertexCount;
    private int indexCount;

    private float[] distances = new float[0];
    private int[] sortingPointsIndices = new int[0];
    private int[] sortScratch = new int[0];

    public void setQuadSortOrigin(float x, float y, float z) {
        this.sortX = x;
        this.sortY = y;
        this.sortZ = z;
    }

    public SortState getSortState() {
        return new SortState(this.vertexCount, this.sortingPoints);
    }

    public void restoreSortState(QuadSorter.SortState sortState) {
        this.vertexCount = sortState.vertexCount;
        this.sortingPoints = sortState.sortingPoints;
        this.pointCount = sortState.sortingPoints.length / 3;

        this.indexOnly = true;
    }

    private void ensureScratchCapacity(int count) {
        if (this.distances.length < count) {
            int newCap = Math.max(count, this.distances.length * 2);
            this.distances = new float[newCap];
            this.sortingPointsIndices = new int[newCap];
            this.sortScratch = new int[newCap];
        }
    }

    public void setupQuadSortingPoints(long bufferPtr, int vertexCount, VertexFormat format) {
        this.vertexCount = vertexCount;
        int pointCount = vertexCount / 4;
        this.pointCount = pointCount;
        float[] sortingPoints = new float[pointCount * 3];

        int vertexSize = format.getVertexSize();
        int quadStride = vertexSize * 4;
        int offset = vertexSize * 2;

        if (format == CustomVertexFormat.COMPRESSED_TERRAIN) {
            final float invConv = 1.0f / VertexBuilder.CompressedVertexBuilder.POS_CONV_MUL;
            final float convOffset = -VertexBuilder.CompressedVertexBuilder.POS_OFFSET;

            for (int m = 0, p = 0; m < pointCount; ++m, p += 3) {
                long ptr = bufferPtr + (long) m * quadStride;

                short x0 = MemoryUtil.memGetShort(ptr + 0);
                short y0 = MemoryUtil.memGetShort(ptr + 2);
                short z0 = MemoryUtil.memGetShort(ptr + 4);
                short x2 = MemoryUtil.memGetShort(ptr + offset + 0);
                short y2 = MemoryUtil.memGetShort(ptr + offset + 2);
                short z2 = MemoryUtil.memGetShort(ptr + offset + 4);

                sortingPoints[p]     = (x0 + x2) * invConv * 0.5f + convOffset;
                sortingPoints[p + 1] = (y0 + y2) * invConv * 0.5f + convOffset;
                sortingPoints[p + 2] = (z0 + z2) * invConv * 0.5f + convOffset;
            }
        } else {
            for (int m = 0, p = 0; m < pointCount; ++m, p += 3) {
                long ptr = bufferPtr + (long) m * quadStride;

                float x0 = MemoryUtil.memGetFloat(ptr + 0);
                float y0 = MemoryUtil.memGetFloat(ptr + 4);
                float z0 = MemoryUtil.memGetFloat(ptr + 8);
                float x2 = MemoryUtil.memGetFloat(ptr + offset + 0);
                float y2 = MemoryUtil.memGetFloat(ptr + offset + 4);
                float z2 = MemoryUtil.memGetFloat(ptr + offset + 8);

                sortingPoints[p]     = (x0 + x2) * 0.5f;
                sortingPoints[p + 1] = (y0 + y2) * 0.5f;
                sortingPoints[p + 2] = (z0 + z2) * 0.5f;
            }
        }

        this.sortingPoints = sortingPoints;
    }

    private void sortQuads() {
        int count = this.pointCount;
        ensureScratchCapacity(count);

        float[] points = this.sortingPoints;
        float[] distances = this.distances;
        int[] indices = this.sortingPointsIndices;
        int[] scratch = this.sortScratch;
        float sx = this.sortX;
        float sy = this.sortY;
        float sz = this.sortZ;

        for (int i = 0, p = 0; i < count; ++i, p += 3) {
            float dx = points[p] - sx;
            float dy = points[p + 1] - sy;
            float dz = points[p + 2] - sz;
            distances[i] = dx * dx + dy * dy + dz * dz;
            indices[i] = i;
            scratch[i] = i;
        }

        SortUtil.mergeSort(indices, distances, 0, count, scratch);
    }

    public void putSortedQuadIndices(TerrainBufferBuilder bufferBuilder, IndexType indexType) {
        sortQuads();
        writeSortedIndices(bufferBuilder.getPtr(), indexType);
    }

    public void putSortedQuadIndices(TerrainBuilder bufferBuilder, IndexType indexType) {
        sortQuads();
        writeSortedIndices(bufferBuilder.indexBufferPtr, indexType);
    }

    private void writeSortedIndices(long ptr, IndexType indexType) {
        int[] indices = this.sortingPointsIndices;
        int count = this.pointCount;
        final int size = indexType.bytes;
        final int stride = 4; // 4 vertices in a quad
        for (int i = 0; i < count; ++i) {
            final int quadIndex = indices[i];
            final int baseVertex = quadIndex * stride;

            MemoryUtil.memPutInt(ptr + (size * 0L), baseVertex + 0);
            MemoryUtil.memPutInt(ptr + (size * 1L), baseVertex + 1);
            MemoryUtil.memPutInt(ptr + (size * 2L), baseVertex + 2);
            MemoryUtil.memPutInt(ptr + (size * 3L), baseVertex + 2);
            MemoryUtil.memPutInt(ptr + (size * 4L), baseVertex + 3);
            MemoryUtil.memPutInt(ptr + (size * 5L), baseVertex + 0);

            ptr += size * 6L;
        }
    }

    public void reset() {
        this.vertexCount = 0;
        this.pointCount = 0;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public static class SortState {
        final int vertexCount;
        final float[] sortingPoints;

        SortState(int vertexCount, float[] sortingPoints) {
            this.vertexCount = vertexCount;
            this.sortingPoints = sortingPoints;
        }
    }
}
