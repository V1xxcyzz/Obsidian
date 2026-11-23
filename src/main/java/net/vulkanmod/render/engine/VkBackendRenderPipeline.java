package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

import java.util.List;

public class VkBackendRenderPipeline implements BackendRenderPipeline {
    private final GraphicsPipeline graphicsPipeline;
    private final BackendRenderPipeline.CreateInfo createInfo;
    private boolean closed;

    private VkBackendRenderPipeline(GraphicsPipeline graphicsPipeline, BackendRenderPipeline.CreateInfo createInfo) {
        this.graphicsPipeline = graphicsPipeline;
        this.createInfo = createInfo;
    }

    public static VkBackendRenderPipeline compile(BackendRenderPipeline.CreateInfo createInfo) {
        Pipeline.Builder builder = Pipeline.builder(createInfo);
        GraphicsPipeline graphicsPipeline = builder.createGraphicsPipeline();
        return new VkBackendRenderPipeline(graphicsPipeline, createInfo);
    }

    public GraphicsPipeline graphicsPipeline() {
        return this.graphicsPipeline;
    }

    public BackendRenderPipeline.CreateInfo createInfo() {
        return this.createInfo;
    }

    public List<BindGroupLayout.UniformDescription> uniforms() {
        return this.createInfo.uniforms();
    }

    public DepthStencilState depthStencilState() {
        return this.createInfo.depthStencilState();
    }

    public PolygonMode polygonMode() {
        return this.createInfo.polygonMode();
    }

    public boolean cull() {
        return this.createInfo.cull();
    }

    public List<ColorTargetState> colorTargetStates() {
        return this.createInfo.colorTargetStates();
    }

    public PrimitiveTopology primitiveTopology() {
        return this.createInfo.primitiveTopology();
    }

    public int pushConstantsSize() {
        return this.createInfo.pushConstantsSize();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.graphicsPipeline.cleanUp();
        }
    }
}
