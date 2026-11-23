package net.vulkanmod.render.profiling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.memory.MemoryManager;

/**
 * Lightweight Sodium-style corner HUD showing FPS, VRAM usage, and visible chunk count.
 */
public final class HudOverlay {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();

    private HudOverlay() {
    }

    public static void render(GuiGraphicsExtractor guiGraphics, int mode) {
        if (mode <= 0 || MINECRAFT.font == null) {
            return;
        }

        Font font = MINECRAFT.font;
        int fps = MINECRAFT.getFps();
        String text;
        if (mode == 1) {
            text = fps + " FPS";
        } else {
            MemoryManager mm = MemoryManager.getInstance();
            int usedVram = mm != null ? mm.getAllocatedDeviceMemoryMB() : 0;
            int totalVram = mm != null ? mm.getDeviceMemoryMB() : 0;
            WorldRenderer wr = WorldRenderer.getInstance();
            int visibleChunks = wr != null ? wr.getVisibleSectionsCount() : 0;
            text = String.format("%d FPS | VRAM %d/%d MB | C: %d", fps, usedVram, totalVram, visibleChunks);
        }

        int padX = 4;
        int padY = 3;
        int x = 4;
        int y = 4;
        int w = font.width(text) + padX * 2;
        int h = font.lineHeight + padY * 2 - 1;

        guiGraphics.fill(x, y, x + w, y + h, 0x900A0A10);
        guiGraphics.fill(x, y, x + 2, y + h, 0xFF9B5DE5);
        guiGraphics.text(font, text, x + padX + 1, y + padY, 0xFFEAEAF0, true);
    }

    public static boolean shouldRender() {
        return Initializer.CONFIG != null && Initializer.CONFIG.hudMode > 0;
    }
}
