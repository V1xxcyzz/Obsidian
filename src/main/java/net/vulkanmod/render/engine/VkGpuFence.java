package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

/**
 * Vulkan-backed Blaze3D GpuFence tracking frame submission and completion.
 */
@Environment(EnvType.CLIENT)
public class VkGpuFence implements GpuFence {
    private final int creationFrame;
    private volatile boolean completed;
    private boolean closed;

    public VkGpuFence() {
        this.creationFrame = Renderer.getCurrentFrame();
        this.completed = !Renderer.isRecording();

        if (!this.completed) {
            MemoryManager.getInstance().addFrameOp(() -> this.completed = true);
        }
    }

    @Override
    public boolean awaitCompletion(long timeoutNs) {
        if (this.closed || this.completed) {
            return true;
        }

        if (timeoutNs <= 0L) {
            return false;
        }

        // If we are still recording the same frame this fence was created in,
        // flush commands so the GPU actually executes up to this fence point.
        if (Renderer.isRecording() && Renderer.getCurrentFrame() == this.creationFrame) {
            Renderer.getInstance().flushCmds();
            this.completed = true;
            return true;
        }

        long fence = Renderer.getInstance().getInFlightFence(this.creationFrame);
        if (fence != 0L) {
            int result = vkWaitForFences(Vulkan.getVkDevice(), fence, true, timeoutNs);
            if (result == VK_SUCCESS) {
                this.completed = true;
                return true;
            }
            return false;
        }

        this.completed = true;
        return true;
    }

    @Override
    public void close() {
        this.closed = true;
        this.completed = true;
    }
}
