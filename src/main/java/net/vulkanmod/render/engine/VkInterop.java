package net.vulkanmod.render.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Public interoperability facade for external OpenGL-to-Vulkan translation layers
 * (such as Omni) to safely share Obsidian's Vulkan command stream, render passes,
 * and Blaze3D state caches.
 */
public final class VkInterop {
    private VkInterop() {
    }

    /**
     * Invalidates all cached Vulkan pipeline, descriptor set, vertex buffer, and index buffer
     * bindings held by Obsidian's Renderer and Blaze3D CommandEncoder. Call this after issuing
     * external raw Vulkan draw commands into the active command buffer.
     */
    public static void invalidateEncoderState() {
        Renderer renderer = Renderer.getInstance();
        if (renderer != null) {
            renderer.invalidateBoundState();
        }

        if (RenderSystem.getDevice().backend instanceof VkGpuDevice gpuDevice) {
            gpuDevice.getEncoder().invalidateCachedState();
        }
    }

    /**
     * Returns the currently recording primary frame VkCommandBuffer, or null if not recording.
     */
    @Nullable
    public static VkCommandBuffer getActiveCommandBuffer() {
        return Renderer.isRecording() ? Renderer.getCommandBuffer() : null;
    }

    /**
     * Returns true if Obsidian is currently recording frame commands.
     */
    public static boolean isRecording() {
        return Renderer.isRecording();
    }

    /**
     * Ends the currently bound Vulkan render pass (if any) so external callers can safely
     * record transfer or compute commands, and invalidates cached encoder state.
     */
    public static void endActiveRenderPass() {
        Renderer renderer = Renderer.getInstance();
        if (renderer != null) {
            renderer.endRenderPass();
        }
        invalidateEncoderState();
    }

    /**
     * Submits any pending transfer command buffers (Blaze3D buffer writes, image uploads,
     * and chunk uploads) on the GPU transfer/graphics queues.
     */
    public static void flushPendingTransfers() {
        Renderer renderer = Renderer.getInstance();
        if (renderer != null) {
            renderer.submitUploads();
        }
        if (UploadManager.INSTANCE != null) {
            UploadManager.INSTANCE.submitUploads();
        }
    }

    /**
     * Wraps an existing Obsidian Vulkan Buffer as a Blaze3D VkGpuBuffer.
     */
    public static VkGpuBuffer wrapBuffer(Buffer buffer) {
        return VkGpuBuffer.from(buffer);
    }

    /**
     * Wraps an existing Obsidian VulkanImage as a Blaze3D VkGpuTexture.
     */
    @Nullable
    public static VkGpuTexture wrapVulkanImage(VulkanImage image) {
        if (RenderSystem.getDevice().backend instanceof VkGpuDevice gpuDevice) {
            return gpuDevice.gpuTextureFromVulkanImage(image);
        }
        return null;
    }
}
