package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.lwjgl.vulkan.VK10;

public class VkConst {

    public static int of(AddressMode addressMode) {
        return VulkanConst.toVk(addressMode);
    }

    public static int of(FilterMode filterMode) {
        return VulkanConst.toVk(filterMode);
    }

    public static int vkFormat(GpuFormat format) {
        return VulkanConst.toVk(format);
    }

    public static GpuFormat gpuFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_B8G8R8A8_UNORM, VK10.VK_FORMAT_R8G8B8A8_SRGB -> GpuFormat.RGBA8_UNORM;
            case VK10.VK_FORMAT_R8_UNORM -> GpuFormat.R8_UNORM;
            case VK10.VK_FORMAT_D32_SFLOAT -> GpuFormat.D32_FLOAT;
            case VK10.VK_FORMAT_D24_UNORM_S8_UINT -> GpuFormat.D24_UNORM_S8_UINT;
            case VK10.VK_FORMAT_D32_SFLOAT_S8_UINT -> GpuFormat.D32_FLOAT_S8_UINT;
            case VK10.VK_FORMAT_D16_UNORM -> GpuFormat.D16_UNORM;
            case VK10.VK_FORMAT_S8_UINT -> GpuFormat.S8_UINT;
            default -> GpuFormat.RGBA8_UNORM;
        };
    }

    public static int toVkDepthOp(CompareOp fn) {
        return VulkanConst.toVk(fn);
    }

    public static int toVkBlendFactor(BlendFactor f) {
        return VulkanConst.toVk(f);
    }

    public static int toVkBlendOp(BlendOp op) {
        return VulkanConst.toVk(op);
    }

    public static int toVkTopology(PrimitiveTopology topology) {
        return switch (topology) {
            case LINES, DEBUG_LINES, DEBUG_LINE_STRIP -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case POINTS -> VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case QUADS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }
}
