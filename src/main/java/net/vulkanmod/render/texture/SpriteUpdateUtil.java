package net.vulkanmod.render.texture;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class SpriteUpdateUtil {

    private static boolean doUpload = true;
    private static final Set<VulkanImage> transitionedLayouts = new HashSet<>();

    private static volatile AnimatedSpriteEntry[] blockAnimatedSprites = new AnimatedSpriteEntry[0];
    private static volatile Reference2IntOpenHashMap<SpriteContents.AnimationState> stateToIndex = new Reference2IntOpenHashMap<>();
    private static volatile Reference2IntOpenHashMap<TextureAtlasSprite> spriteToIndex = new Reference2IntOpenHashMap<>();
    private static volatile int currentTick = 0;

    public static final class AnimatedSpriteEntry {
        public final float u0, u1, v0, v1;
        public final SpriteContents.AnimationState state;
        public int lastActiveTick = -1;

        AnimatedSpriteEntry(float u0, float u1, float v0, float v1, SpriteContents.AnimationState state) {
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
            this.state = state;
        }

        public boolean contains(float u, float v) {
            return u >= this.u0 && u <= this.u1 && v >= this.v0 && v <= this.v1;
        }
    }

    public static void advanceTick() {
        currentTick++;
    }

    public static int getCurrentTick() {
        return currentTick;
    }

    public static void registerBlockAtlasSprites(List<TextureAtlasSprite> sprites, List<SpriteContents.AnimationState> states) {
        if (states == null || states.isEmpty()) {
            blockAnimatedSprites = new AnimatedSpriteEntry[0];
            stateToIndex = new Reference2IntOpenHashMap<>();
            spriteToIndex = new Reference2IntOpenHashMap<>();
            return;
        }

        AnimatedSpriteEntry[] entries = new AnimatedSpriteEntry[states.size()];
        Reference2IntOpenHashMap<SpriteContents.AnimationState> stateMap = new Reference2IntOpenHashMap<>(states.size());
        stateMap.defaultReturnValue(-1);
        Reference2IntOpenHashMap<TextureAtlasSprite> spriteMap = new Reference2IntOpenHashMap<>(states.size());
        spriteMap.defaultReturnValue(-1);

        int stateIdx = 0;
        for (int i = 0; i < sprites.size() && stateIdx < states.size(); i++) {
            TextureAtlasSprite sprite = sprites.get(i);
            if (sprite != null && sprite.isAnimated()) {
                SpriteContents.AnimationState state = states.get(stateIdx);
                entries[stateIdx] = new AnimatedSpriteEntry(sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), state);
                stateMap.put(state, stateIdx);
                spriteMap.put(sprite, stateIdx);
                stateIdx++;
            }
        }

        blockAnimatedSprites = entries;
        stateToIndex = stateMap;
        spriteToIndex = spriteMap;
    }

    public static AnimatedSpriteEntry[] getBlockAnimatedSprites() {
        return blockAnimatedSprites;
    }

    public static int findAnimatedSpriteIndex(float u, float v) {
        AnimatedSpriteEntry[] entries = blockAnimatedSprites;
        for (int i = 0; i < entries.length; i++) {
            AnimatedSpriteEntry entry = entries[i];
            if (entry != null && entry.contains(u, v)) {
                return i;
            }
        }
        return -1;
    }

    public static int findAnimatedSpriteIndex(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return -1;
        }
        return spriteToIndex.getInt(sprite);
    }

    public static void markActiveSprites(int[] indices) {
        if (indices == null) {
            return;
        }
        AnimatedSpriteEntry[] entries = blockAnimatedSprites;
        int tick = currentTick;
        for (int idx : indices) {
            if (idx >= 0 && idx < entries.length) {
                AnimatedSpriteEntry entry = entries[idx];
                if (entry != null) {
                    entry.lastActiveTick = tick;
                }
            }
        }
    }

    public static int[] toIntArray(BitSet bits) {
        if (bits.isEmpty()) {
            return null;
        }
        int[] result = new int[bits.cardinality()];
        int pos = 0;
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            result[pos++] = i;
        }
        return result;
    }

    public static boolean isAnimationStateActive(Identifier atlasLocation, SpriteContents.AnimationState state) {
        if (!Initializer.CONFIG.animateOnlyVisibleTextures || !TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation)) {
            return true;
        }
        int idx = stateToIndex.getInt(state);
        if (idx < 0) {
            return true;
        }
        AnimatedSpriteEntry[] entries = blockAnimatedSprites;
        if (idx >= entries.length || entries[idx] == null) {
            return true;
        }
        return entries[idx].lastActiveTick >= currentTick - 1;
    }

    public static void setDoUpload(boolean b) {
        doUpload = b;
    }

    public static boolean doUploadFrame() {
        return doUpload;
    }

    public static void addTransitionedLayout(VulkanImage image) {
        transitionedLayouts.add(image);
    }

    public static void transitionLayouts() {
        if (transitionedLayouts.isEmpty()) {
            return;
        }

        VkCommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer().handle;

        transitionedLayouts.forEach(
                image ->
                {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        image.readOnlyLayout(stack, commandBuffer);
                    }

                });
        transitionedLayouts.clear();
    }
}
