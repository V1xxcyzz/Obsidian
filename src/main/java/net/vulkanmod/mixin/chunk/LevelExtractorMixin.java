package net.vulkanmod.mixin.chunk;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow private ClientLevel level;
    @Shadow private int lastViewDistance;

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void onSetLevel(@Nullable ClientLevel clientLevel, CallbackInfo ci) {
        if (WorldRenderer.getInstance() != null) {
            WorldRenderer.getInstance().setLevel(clientLevel);
        }
    }

    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void onAllChanged(CallbackInfo ci) {
        if (this.level != null) {
            this.level.clearTintCaches();
        }
        this.lastViewDistance = this.minecraft.options.getEffectiveRenderDistance();
        if (WorldRenderer.getInstance() != null && WorldRenderer.getLevel() == this.level) {
            WorldRenderer.getInstance().allChanged();
        }
        ci.cancel();
    }

    /**
     * @author Obsidian
     * @reason Frustum culling and section visibility are handled by Obsidian's WorldRenderer
     */
    @Overwrite
    private void applyFrustum(Frustum frustum) {
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void setupRenderer(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer != null) {
            worldRenderer.setPartialTick(partialTick);
            boolean spectator = this.minecraft.player != null && this.minecraft.player.isSpectator();
            worldRenderer.setupRenderer(camera, camera.getCullFrustum(), camera.getCapturedFrustum() != null, spectator);
        }
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), cancellable = true)
    private void onExtractVisibleBlockEntities(Camera camera, float partialTick, LevelRenderState levelRenderState,
                                               CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void setSectionDirty(int x, int y, int z, boolean flag) {
        if (WorldRenderer.getInstance() != null) {
            WorldRenderer.getInstance().setSectionDirty(x, y, z, flag);
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public String sectionStatistics() {
        return WorldRenderer.getInstance() != null ? WorldRenderer.getInstance().getChunkStatistics() : null;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public int countRenderedSections() {
        return WorldRenderer.getInstance() != null ? WorldRenderer.getInstance().getVisibleSectionsCount() : 0;
    }
}
