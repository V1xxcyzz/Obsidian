package net.vulkanmod.mixin.render.vertex;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.mixin.matrix.PoseAccessor;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderM
        implements VertexConsumer, ExtendedVertexBuilder {

    private static final int SEMANTIC_COLOR = 1;
    private static final int SEMANTIC_UV0 = 2;
    private static final int SEMANTIC_UV1 = 3;
    private static final int SEMANTIC_UV2 = 4;
    private static final int SEMANTIC_NORMAL = 6;

    @Shadow private boolean blockFormat;
    @Shadow private boolean entityFormat;
    @Shadow private VertexFormat format;

    @Shadow protected abstract long beginVertex();

    @Shadow private int elementsToFill;
    @Shadow @Final private int initialElementsToFill;

    @Shadow protected abstract long beginElement(int semanticId);

    @Unique private long ptr;

    @Override
    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        this.ptr = this.beginVertex();

        if (this.entityFormat) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, packedColor);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, overlay);
            MemoryUtil.memPutInt(ptr + 28, light);
            MemoryUtil.memPutInt(ptr + 32, packedNormal);
        } else if (this.blockFormat) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, packedColor);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, light);
        } else {
            this.elementsToFill = this.initialElementsToFill;

            this.position(x, y, z);
            this.fastColor(packedColor);
            this.fastUv(u, v);
            this.fastOverlay(overlay);
            this.light(light);
            this.fastNormal(packedNormal);
        }
    }

    @Override
    public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
        this.ptr = this.beginVertex();

        MemoryUtil.memPutFloat(ptr + 0, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutFloat(ptr + 12, u);
        MemoryUtil.memPutFloat(ptr + 16, v);
        MemoryUtil.memPutInt(ptr + 20, packedColor);
        MemoryUtil.memPutInt(ptr + 24, light);
    }

    public void position(float x, float y, float z) {
        MemoryUtil.memPutFloat(ptr + 0, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
    }

    public void fastColor(int packedColor) {
        long ptr = this.beginElement(SEMANTIC_COLOR);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, packedColor);
        }
    }

    public void fastUv(float u, float v) {
        long ptr = this.beginElement(SEMANTIC_UV0);
        if (ptr != -1L) {
            MemoryUtil.memPutFloat(ptr, u);
            MemoryUtil.memPutFloat(ptr + 4, v);
        }
    }

    public void fastOverlay(int o) {
        long ptr = this.beginElement(SEMANTIC_UV1);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, o);
        }
    }

    public void light(int l) {
        long ptr = this.beginElement(SEMANTIC_UV2);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, l);
        }
    }

    public void fastNormal(int packedNormal) {
        long ptr = this.beginElement(SEMANTIC_NORMAL);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, packedNormal);
        }
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        long m = this.beginElement(SEMANTIC_COLOR);
        if (m != -1L) {
            int color = packRgba(r, g, b, a);
            MemoryUtil.memPutInt(m, color);
        }

        return this;
    }

    @Unique private final float[] brightness = new float[4];
    @Unique private final int[] lights = new int[4];

    public void putBulkData(PoseStack.Pose pose, BakedQuad bakedQuad, float r, float g, float b, float a, int light, int overlay) {
        brightness[0] = 1.0f; brightness[1] = 1.0f; brightness[2] = 1.0f; brightness[3] = 1.0f;
        lights[0] = light; lights[1] = light; lights[2] = light; lights[3] = light;

        this.putBulkData(pose, bakedQuad, brightness, r, g, b, a, lights, overlay);
    }

    public void putBulkData(PoseStack.Pose pose, BakedQuad bakedQuad, float[] brightness, float r, float g, float b, float a,
                            int[] lights, int overlay) {
        Vector3fc vector3fc = bakedQuad.direction().getUnitVec3f();
        Matrix4f matrix4f = pose.pose();
        boolean trustedNormals = ((PoseAccessor)(Object)pose).trustedNormals();
        int packedNormal = MathUtil.packTransformedNorm(pose.normal(), trustedNormals, vector3fc.x(), vector3fc.y(), vector3fc.z());

        int lightEmission = bakedQuad.materialInfo().lightEmission();

        for (int l = 0; l < 4; l++) {
            Vector3fc quadPos = bakedQuad.position(l);
            long packedUV = bakedQuad.packedUV(l);
            float br = brightness[l];
            int color = ColorUtil.RGBA.pack(r * br, g * br, b * br, a);
            int light = LightCoordsUtil.lightCoordsWithEmission(lights[l], lightEmission);

            float x = quadPos.x();
            float y = quadPos.y();
            float z = quadPos.z();
            float tx = MathUtil.transformX(matrix4f, x, y, z);
            float ty = MathUtil.transformY(matrix4f, x, y, z);
            float tz = MathUtil.transformZ(matrix4f, x, y, z);

            float u = UVPair.unpackU(packedUV);
            float v = UVPair.unpackV(packedUV);
            this.vertex(tx, ty, tz, color, u, v, overlay, light, packedNormal);
        }
    }

    private static int packRgba(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }
}
