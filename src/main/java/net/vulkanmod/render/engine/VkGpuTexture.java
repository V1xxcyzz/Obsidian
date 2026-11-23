package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

@Environment(EnvType.CLIENT)
public class VkGpuTexture extends BaseGpuTexture {
    protected VkGlTexture glTexture;
    protected final int id;
    private final it.unimi.dsi.fastutil.ints.Int2ReferenceMap<VkFbo> fboCache = new it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap<>();
    protected boolean closed;
    private int views;

    VkTextureView fboView;
    boolean needsClear = false;
    int clearColor = 0;
    float depthClearValue = 1.0f;

    protected VkGpuTexture(int usage, String label, GpuFormat format, int width, int height, int layers, int mipLevels, int id, VkGlTexture glTexture) {
        super(usage, label, format, width, height, layers, mipLevels);
        this.id = id;
        this.glTexture = glTexture;
    }

    public void addViews() {
        this.views++;
    }

    public void removeViews() {
        this.views--;
        if (this.closed && this.views <= 0) {
            this.destroyImage();
        }
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            for (VkFbo fbo : this.fboCache.values()) {
                fbo.close();
            }
            this.fboCache.clear();
            if (this.views <= 0) {
                this.destroyImage();
            }
        }
    }

    private void destroyImage() {
        VkGlTexture.glDeleteTextures(this.id);
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    public int glId() {
        return this.id;
    }

    public void setClearColor(int clearColor) {
        this.needsClear = true;
        this.clearColor = clearColor;
    }

    public void setDepthClearValue(float depthClearValue) {
        this.needsClear = true;
        this.depthClearValue = depthClearValue;
    }

    public boolean needsClear() {
        return needsClear;
    }

    public VkFbo getFbo(@Nullable GpuTexture depthAttachment) {
        int depthAttachmentId = depthAttachment == null ? 0 : ((VkGpuTexture) depthAttachment).id;

        if (this.fboView == null) {
            this.fboView = (VkTextureView) VkGpuDevice.getInstance().createTextureView(this, 0, this.getMipLevels());
        }

        return this.fboCache.computeIfAbsent(depthAttachmentId, j -> new VkFbo(this.fboView, (VkGpuTexture) depthAttachment));
    }

    public VulkanImage getVulkanImage() {
        return glTexture.getVulkanImage();
    }

    public static GpuFormat textureFormat(int vkFormat) {
        return VkConst.gpuFormat(vkFormat);
    }

    public static int vkFormat(GpuFormat format) {
        return VkConst.vkFormat(format);
    }

    public static int vkImageViewType(int usage) {
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            return VK10.VK_IMAGE_VIEW_TYPE_CUBE;
        }
        return VK10.VK_IMAGE_VIEW_TYPE_2D;
    }
}
