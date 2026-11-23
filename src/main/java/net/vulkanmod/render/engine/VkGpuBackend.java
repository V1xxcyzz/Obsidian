package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.sdl.SDLVideo;

public class VkGpuBackend implements GpuBackend {
    public static final VkGpuBackend INSTANCE = new VkGpuBackend();
    private static final long SDL_WINDOW_VULKAN = 0x10000000L;

    private long windowHandle;

    private VkGpuBackend() {
    }

    @Override
    public String getName() {
        return "Obsidian Vulkan";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(String title, int width, int height, long flags) {
        this.windowHandle = SDLVideo.SDL_CreateWindow(title, width, height, flags | SDL_WINDOW_VULKAN);
        return this.windowHandle;
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        VRenderSystem.setWindow(this.windowHandle);
        VRenderSystem.initRenderer();

        VkGpuDevice backend = new VkGpuDevice(this.windowHandle, options);
        Renderer.initRenderer();

        return new FrontendGpuDevice(backend);
    }
}
