package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class VkGpuDevice implements GpuDeviceBackend {
    private static VkGpuDevice INSTANCE;

    private final VkCommandEncoder encoder;
    private final VkDebugLabel debugLabels;
    private final int maxSupportedTextureSize;
    private final int uniformOffsetAlignment;
    private final Set<String> enabledExtensions = new HashSet<>();
    private final DeviceInfo deviceInfo;

    public VkGpuDevice(long windowHandle, GpuDebugOptions options) {
        INSTANCE = this;
        this.debugLabels = VkDebugLabel.create(options != null && options.useLabels(), this.enabledExtensions);
        this.maxSupportedTextureSize = VRenderSystem.maxSupportedTextureSize();
        this.uniformOffsetAlignment = (int) DeviceManager.deviceProperties().limits().minUniformBufferOffsetAlignment();
        this.encoder = new VkCommandEncoder(this);

        Device dev = Vulkan.getDevice();
        DeviceLimits limits = new DeviceLimits(
                Math.max(1, (int) DeviceManager.deviceProperties().limits().maxSamplerAnisotropy()),
                Math.max(1, this.uniformOffsetAlignment),
                this.maxSupportedTextureSize,
                256L * 1024L * 1024L,
                64,
                Math.max(1, DeviceManager.deviceProperties().limits().maxColorAttachments()),
                Math.max(1, DeviceManager.deviceProperties().limits().maxDrawIndirectCount())
        );
        DeviceFeatures features = new DeviceFeatures(
                true,   // wireframeFillMode
                true,   // shaderDrawParameters
                false,  // multiDrawDirectInterleaved
                false,  // multiDrawDirectSeparate
                dev.availableFeatures.features().multiDrawIndirect(),
                dev.availableFeatures.features().multiDrawIndirect(),
                true,   // nonZeroFirstInstance
                true    // persistentMapping
        );
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
        DeviceType type = switch (dev.deviceType) {
            case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DeviceType.INTEGRATED;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DeviceType.DISCRETE;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DeviceType.VIRTUAL;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> DeviceType.CPU;
            default -> DeviceType.OTHER;
        };

        this.deviceInfo = new DeviceInfo(
                dev.deviceName,
                dev.vendorIdString,
                "Vulkan " + dev.vkVersion + " (Driver " + dev.driverVersion + ")",
                true,
                "Obsidian Vulkan",
                Math.max(1.0f, DeviceManager.deviceProperties().limits().timestampPeriod()),
                limits,
                features,
                this.enabledExtensions,
                hints,
                type
        );
    }

    public static VkGpuDevice getInstance() {
        return INSTANCE;
    }

    public VkDebugLabel debugLabels() {
        return this.debugLabels;
    }

    public VkCommandEncoder getEncoder() {
        return this.encoder;
    }

    public int getUniformOffsetAlignment() {
        return this.uniformOffsetAlignment;
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier shouldClose) {
        VRenderSystem.setWindow(windowHandle);
        Vulkan.createSurface(windowHandle);
        DeviceManager.initSurfaceProperties();
        if (Renderer.getInstance() == null) {
            Renderer.initRenderer();
        }
        return new VkGpuSurface(this, windowHandle, shouldClose);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
                                    FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return new VkSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        if (mipLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be at least 1");
        }
        int id = VkGlTexture.genTextureId();
        if (label == null) {
            label = String.valueOf(id);
        }

        int vkFormat = VkGpuTexture.vkFormat(format);
        int viewType = VkGpuTexture.vkImageViewType(usage);
        boolean depthFormat = VulkanImage.isDepthFormat(vkFormat);
        int attachmentUsage = depthFormat ? VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;

        VulkanImage texture = VulkanImage.builder(width, height)
                .setName(label)
                .setFormat(vkFormat)
                .setArrayLayers(depthOrLayers)
                .setMipLevels(mipLevels)
                .addUsage(attachmentUsage)
                .setViewType(viewType)
                .createVulkanImage();

        VkGlTexture vGlTexture = VkGlTexture.getTexture(id);
        vGlTexture.setVulkanImage(texture);
        VkGlTexture.bindTexture(id);

        VkGpuTexture vkTexture = new VkGpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels, id, vGlTexture);
        this.debugLabels.applyLabel(vkTexture);
        return vkTexture;
    }

    public VkGpuTexture gpuTextureFromVulkanImage(VulkanImage image) {
        int id = VkGlTexture.genTextureId();
        VkGlTexture glTexture = VkGlTexture.getTexture(id);
        glTexture.setVulkanImage(image);
        GpuFormat gpuFormat = VkGpuTexture.textureFormat(image.format);
        VkGpuTexture gpuTexture = new VkGpuTexture(0, image.name, gpuFormat, image.width, image.height, 1, image.mipLevels, id, glTexture);
        this.debugLabels.applyLabel(gpuTexture);
        return gpuTexture;
    }

    public GpuTextureView createTextureView(GpuTexture gpuTexture) {
        return this.createTextureView(gpuTexture, 0, gpuTexture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
        if (gpuTexture.isClosed()) {
            throw new IllegalArgumentException("Can't create texture view with closed texture");
        }
        if (baseMipLevel < 0 || baseMipLevel + mipLevels > gpuTexture.getMipLevels()) {
            throw new IllegalArgumentException(
                    mipLevels + " mip levels starting from " + baseMipLevel + " would be out of range for texture with only " + gpuTexture.getMipLevels() + " mip levels"
            );
        }
        return new VkTextureView((VkGpuTexture) gpuTexture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be greater than zero");
        }
        return new VkGpuBuffer(this.debugLabels, label, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        if (!data.hasRemaining()) {
            throw new IllegalArgumentException("Buffer source must not be empty");
        }
        VkGpuBuffer buffer = new VkGpuBuffer(this.debugLabels, label, usage, data.remaining());
        this.encoder.writeToBuffer(buffer.slice(), data);
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return Collections.emptyList();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
        return () -> VkBackendRenderPipeline.compile(createInfo);
    }

    @Override
    public void close() {
        this.encoder.transientMemory().close();
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int count) {
        return new VkQueryPool(count);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }
}
