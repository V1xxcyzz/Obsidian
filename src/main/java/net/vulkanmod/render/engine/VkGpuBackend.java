package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;

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
        if (!SDLVulkan.SDL_Vulkan_LoadLibrary((CharSequence) null)) {
            throw new BackendCreationException("Failed to load Vulkan library", BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        }
    }

    @Override
    public void unloadLibrary() {
        SDLVulkan.SDL_Vulkan_UnloadLibrary();
    }

    @Override
    public long createWindow(String title, int width, int height, long flags) {
        this.windowHandle = SDLVideo.SDL_CreateWindow(title, width, height, flags | SDL_WINDOW_VULKAN);
        VRenderSystem.setWindow(this.windowHandle);
        return this.windowHandle;
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        VRenderSystem.initDevice();

        VkGpuDevice backend = new VkGpuDevice(this.windowHandle, options);

        return new FrontendGpuDevice(backend);
    }
}
