package net.vulkanmod.mixin.chunk;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.engine.VkInterop;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow @Final private EntityRenderDispatcher entityRenderDispatcher;
    @Shadow @Final private BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    @Shadow @Final private RenderBuffers renderBuffers;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow @Final private SubmitNodeStorage submitNodeStorage;
    @Shadow @Final private WorldBorderRenderer worldBorderRenderer;
    @Shadow private ViewArea viewArea;

    @Unique private WorldRenderer worldRenderer;
    @Unique private long lastCameraSection = Long.MIN_VALUE;

    @Unique double camX, camY, camZ;
    @Unique Matrix4f modelView, projection;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(EntityRenderDispatcher entityRenderDispatcher, BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                      ModelManager modelManager, TextureManager textureManager, AtlasManager atlasManager,
                      ShaderManager shaderManager, GameRenderer gameRenderer, int i, int j, CallbackInfo ci) {
        this.worldRenderer = WorldRenderer.init(this.entityRenderDispatcher, this.blockEntityRenderDispatcher, this.renderBuffers, this.levelRenderState, this.featureRenderDispatcher);
    }

    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"), cancellable = true)
    private void skipInvalidateCompiledGeometry(ClientLevel clientLevel, Options options, Camera camera, BlockColors blockColors, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void onRepositionCamera(CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (this.viewArea == null) {
            long section = SectionPos.of(cameraRenderState.pos).asLong();
            if (section != this.lastCameraSection) {
                this.lastCameraSection = section;
                this.worldBorderRenderer.invalidate();
            }
            ci.cancel();
        }
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void skipSectionOcclusionGraphUpdate(SectionOcclusionGraph instance, CameraRenderState cameraRenderState,
                                                 int fov, ChunkLoadingRenderState chunkLoadingRenderState) {
    }

    @Inject(method = "submitBlockEntities", at = @At("RETURN"))
    private void onSubmitBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                       SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        this.worldRenderer.renderBlockEntities(poseStack, levelRenderState, this.submitNodeStorage,
                level != null ? level.destructionProgress() : Long2ObjectMaps.emptyMap());
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public boolean isSectionCompiledAndVisible(BlockPos blockPos, long l) {
        return this.worldRenderer.isSectionCompiled(blockPos);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void updateMatrices(GraphicsResourceAllocator graphicsResourceAllocator, boolean bl,
                                CameraRenderState cameraRenderState, GpuBufferSlice gpuBufferSlice,
                                Vector4f vector4f, boolean bl2, boolean bl3, CallbackInfo ci) {
        this.modelView = new Matrix4f(cameraRenderState.viewRotationMatrix);
        this.projection = new Matrix4f(cameraRenderState.projectionMatrix);
        this.camX = cameraRenderState.pos.x();
        this.camY = cameraRenderState.pos.y();
        this.camZ = cameraRenderState.pos.z();
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public ChunkSectionsToRender prepareChunkRenders(Matrix4fc matrix4fc, boolean bl) {
        return null;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public ChunkSectionsToRender prepareChunkRendersIndirect(Matrix4fc matrix4fc, boolean bl) {
        return null;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void compileSections(CameraRenderState cameraRenderState) {
        this.worldRenderer.uploadSections();
    }

    @Redirect(method = {"executeSolid", "executeClassicTransparency"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
    private void renderSectionLayer(ChunkSectionsToRender instance, ChunkSectionLayerGroup chunkSectionLayerGroup,
                                    RenderPass renderPass, GpuSampler gpuSampler, GpuTextureView gpuTextureView, boolean wireframe) {
        if (chunkSectionLayerGroup == ChunkSectionLayerGroup.OPAQUE) {
            Profiler profiler = Profiler.getMainProfiler();
            profiler.push("Opaque_terrain");

            this.worldRenderer.renderSectionLayer(TerrainRenderType.SOLID, camX, camY, camZ, modelView, projection);
            this.worldRenderer.renderSectionLayer(TerrainRenderType.CUTOUT, camX, camY, camZ, modelView, projection);
            VkInterop.invalidateEncoderState();
        } else if (chunkSectionLayerGroup == ChunkSectionLayerGroup.TRANSLUCENT) {
            Profiler profiler = Profiler.getMainProfiler();
            profiler.pop();
            profiler.push("Translucent_terrain");

            this.worldRenderer.renderSectionLayer(TerrainRenderType.TRANSLUCENT, camX, camY, camZ, modelView, projection);
            VkInterop.invalidateEncoderState();

            profiler.pop();
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public boolean hasRenderedAllSections() {
        return !this.worldRenderer.graphNeedsUpdate() && this.worldRenderer.getTaskDispatcher().isIdle();
    }

}
