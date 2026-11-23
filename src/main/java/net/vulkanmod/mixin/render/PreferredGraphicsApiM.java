package net.vulkanmod.mixin.render;

import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import net.vulkanmod.render.engine.VkGpuBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiM {

    /**
     * @author Obsidian
     * @reason Force Obsidian's Vulkan backend in RenderPearl 26.3
     */
    @Overwrite
    public GpuBackend[] getBackendsToTry() {
        return new GpuBackend[]{ VkGpuBackend.INSTANCE };
    }
}
