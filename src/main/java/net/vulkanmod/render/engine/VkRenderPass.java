package net.vulkanmod.render.engine;

import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.Renderer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class VkRenderPass implements RenderPassBackend {
    protected static final int MAX_VERTEX_BUFFERS = 4;

    private final VkCommandEncoder encoder;
    private final boolean hasDepthTexture;

    @Nullable
    protected VkBackendRenderPipeline pipeline;
    protected final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    protected int maxVertexBufferSlot = -1;
    @Nullable
    protected GpuBuffer indexBuffer;
    protected IndexType indexType = IndexType.INT;
    private final ScissorState scissorState = new ScissorState();
    protected final Int2ObjectOpenHashMap<Object> uniforms = new Int2ObjectOpenHashMap<>();
    protected boolean uniformsDirty = true;
    protected boolean samplersDirty = true;
    @Nullable
    protected ByteBuffer pendingPushConstants;
    protected int pushedDebugGroups;

    public VkRenderPass(VkCommandEncoder commandEncoder, boolean hasDepthTexture) {
        this.encoder = commandEncoder;
        this.hasDepthTexture = hasDepthTexture;
    }

    public boolean hasDepthTexture() {
        return this.hasDepthTexture;
    }

    public @Nullable VkBackendRenderPipeline getPipeline() {
        return this.pipeline;
    }

    @Override
    public void pushDebugGroup(Supplier<String> supplier) {
        this.pushedDebugGroups++;
    }

    @Override
    public void popDebugGroup() {
        if (this.pushedDebugGroups > 0) {
            this.pushedDebugGroups--;
        }
    }

    @Override
    public void setPipeline(BackendRenderPipeline backendPipeline) {
        VkBackendRenderPipeline next = (VkBackendRenderPipeline) backendPipeline;
        if (this.pipeline != next) {
            this.pipeline = next;
            this.uniformsDirty = true;
            this.samplersDirty = true;
        }
    }

    @Override
    public void setUniform(int slot, @Nullable Object value) {
        if (value == null) {
            this.uniforms.remove(slot);
        } else {
            this.uniforms.put(slot, value);
        }
        this.uniformsDirty = true;
        this.samplersDirty = true;
    }

    @Override
    public void pushConstants(ByteBuffer data) {
        if (data == null || !data.hasRemaining()) {
            this.pendingPushConstants = null;
            return;
        }
        ByteBuffer copy = MemoryUtil.memAlloc(data.remaining());
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(copy), data.remaining());
        if (this.pendingPushConstants != null) {
            MemoryUtil.memFree(this.pendingPushConstants);
        }
        this.pendingPushConstants = copy;
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        this.scissorState.enable(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        this.scissorState.disable();
    }

    public boolean isScissorEnabled() {
        return this.scissorState.enabled();
    }

    public int getScissorX() {
        return this.scissorState.x();
    }

    public int getScissorY() {
        return this.scissorState.y();
    }

    public int getScissorWidth() {
        return this.scissorState.width();
    }

    public int getScissorHeight() {
        return this.scissorState.height();
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Vertex buffer slot is out of range: " + slot);
        }
        this.vertexBuffers[slot] = slice;
        if (slice != null && slot > this.maxVertexBufferSlot) {
            this.maxVertexBufferSlot = slot;
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType indexType) {
        this.indexBuffer = buffer;
        this.indexType = indexType != null ? indexType : IndexType.INT;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        this.encoder.executeDraw(this, vertexOffset, firstIndex, indexCount, this.indexType, instanceCount, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer data, int instanceCount, int firstInstance, int drawCount) {
        int pos = data.position();
        for (int i = 0; i < drawCount; i++) {
            int firstIndex = data.get(pos + i * 3);
            int indexCount = data.get(pos + i * 3 + 1);
            int vertexOffset = data.get(pos + i * 3 + 2);
            this.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer indices, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        throw new UnsupportedOperationException("multiDrawDirectSeparate is not enabled");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice indirectBuffer, int drawCount) {
        this.encoder.executeDrawIndexedIndirect(this, indirectBuffer, drawCount);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        this.encoder.executeDraw(this, firstVertex, 0, vertexCount, null, instanceCount, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer data, int instanceCount, int firstInstance, int drawCount) {
        int pos = data.position();
        for (int i = 0; i < drawCount; i++) {
            int firstVertex = data.get(pos + i * 2);
            int vertexCount = data.get(pos + i * 2 + 1);
            this.draw(vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException("multiDrawDirectSeparate is not enabled");
    }

    @Override
    public void drawIndirect(GpuBufferSlice indirectBuffer, int drawCount) {
        this.encoder.executeDrawIndirect(this, indirectBuffer, drawCount);
    }

    @Override
    public void writeTimestamp(GpuQueryPool queryPool, int index) {
        if (queryPool instanceof VkQueryPool vkPool) {
            vkPool.recordTimestamp(index);
        }
    }

    public void finish() {
        if (this.pendingPushConstants != null) {
            MemoryUtil.memFree(this.pendingPushConstants);
            this.pendingPushConstants = null;
        }
    }
}
