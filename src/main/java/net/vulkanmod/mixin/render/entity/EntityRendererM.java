package net.vulkanmod.mixin.render.entity;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public class EntityRendererM<T extends Entity> {

    @Redirect(method = "shouldRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean isVisible(Frustum frustum, AABB aABB) {
        if (!frustum.isVisible(aABB)) {
            return false;
        }

        if (Initializer.CONFIG.entityCulling) {
            WorldRenderer worldRenderer = WorldRenderer.getInstance();
            SectionGrid sectionGrid = worldRenderer.getSectionGrid();
            if (sectionGrid == null) {
                return true;
            }

            short lastFrame = worldRenderer.getLastFrame();
            int minX = SectionPos.posToSectionCoord(aABB.minX) << 4;
            int minY = SectionPos.posToSectionCoord(aABB.minY) << 4;
            int minZ = SectionPos.posToSectionCoord(aABB.minZ) << 4;
            int maxX = SectionPos.posToSectionCoord(aABB.maxX) << 4;
            int maxY = SectionPos.posToSectionCoord(aABB.maxY) << 4;
            int maxZ = SectionPos.posToSectionCoord(aABB.maxZ) << 4;

            for (int y = minY; y <= maxY; y += 16) {
                for (int z = minZ; z <= maxZ; z += 16) {
                    for (int x = minX; x <= maxX; x += 16) {
                        RenderSection section = sectionGrid.getSectionAtBlockPos(x, y, z);
                        if (section == null || section.getLastFrame() == lastFrame) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        return true;
    }
}
