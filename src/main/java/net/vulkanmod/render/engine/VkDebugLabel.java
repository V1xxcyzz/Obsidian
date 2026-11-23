package net.vulkanmod.render.engine;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Set;

@Environment(EnvType.CLIENT)
public class VkDebugLabel {
    private final boolean enabled;

    public VkDebugLabel(boolean enabled) {
        this.enabled = enabled;
    }

    public void applyLabel(VkGpuBuffer glBuffer) {
    }

    public void applyLabel(VkGpuTexture glTexture) {
    }

    public static VkDebugLabel create(boolean enabled, Set<String> extensions) {
        return new VkDebugLabel(enabled);
    }

    public boolean exists() {
        return this.enabled;
    }
}
