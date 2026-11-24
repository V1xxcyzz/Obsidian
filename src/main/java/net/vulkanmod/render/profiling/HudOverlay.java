package net.vulkanmod.render.profiling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.memory.MemoryManager;

import java.util.Arrays;

/**
 * Customizable Sodium-style Obsidian HUD showing FPS, 1% low, frametime, VRAM, chunks,
 * coordinates, ping, and an optional frametime sparkline graph.
 */
public final class HudOverlay {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();

    private static final int SAMPLE_COUNT = 120;
    private static final int GRAPH_SAMPLES = 60;
    private static final float[] FRAME_TIMES_MS = new float[SAMPLE_COUNT];
    private static final float[] SORT_SCRATCH = new float[SAMPLE_COUNT];
    private static int sampleCursor = 0;
    private static int validSamples = 0;
    private static long lastFrameNs = 0L;

    private static long nextTextUpdateMs = 0L;
    private static String linePrimary = "";
    private static String lineSecondary = "";
    private static String lineCoords = "";

    private HudOverlay() {
    }

    public static void render(GuiGraphicsExtractor guiGraphics, int mode) {
        if (mode <= 0 || MINECRAFT.font == null) {
            return;
        }

        recordFrameTiming();

        Config cfg = Initializer.CONFIG;
        long nowMs = System.currentTimeMillis();
        if (nowMs >= nextTextUpdateMs) {
            nextTextUpdateMs = nowMs + 200L;
            rebuildCachedLines(mode, cfg);
        }

        Font font = MINECRAFT.font;
        float scale = cfg != null ? Math.max(0.5f, cfg.hudScale / 100.0f) : 1.0f;
        int posMode = cfg != null ? cfg.hudPosition : 0;
        int style = cfg != null ? cfg.hudStyle : 0;
        boolean showGraph = cfg != null && cfg.hudShowGraph;

        int maxW = font.width(linePrimary);
        int lineCount = 1;
        if (!lineSecondary.isEmpty()) {
            maxW = Math.max(maxW, font.width(lineSecondary));
            lineCount++;
        }
        if (!lineCoords.isEmpty()) {
            maxW = Math.max(maxW, font.width(lineCoords));
            lineCount++;
        }

        int padX = style == 1 ? 2 : 5;
        int padY = style == 1 ? 2 : 4;
        int graphH = showGraph ? 16 : 0;
        int graphGap = showGraph ? 3 : 0;
        int boxW = Math.max(maxW + padX * 2 + (style == 0 ? 2 : 0), showGraph ? GRAPH_SAMPLES + padX * 2 : 0);
        int boxH = lineCount * (font.lineHeight + 1) - 1 + padY * 2 + graphGap + graphH;

        int screenW = (int) (guiGraphics.guiWidth() / scale);
        int screenH = (int) (guiGraphics.guiHeight() / scale);
        int margin = 4;

        int x = (posMode == 1 || posMode == 3) ? Math.max(margin, screenW - boxW - margin) : margin;
        int y = (posMode == 2 || posMode == 3) ? Math.max(margin, screenH - boxH - margin) : margin;

        guiGraphics.pose().pushMatrix();
        if (scale != 1.0f) {
            guiGraphics.pose().scale(scale, scale);
        }

        if (style == 0) {
            guiGraphics.fill(x, y, x + boxW, y + boxH, 0x960A0A10);
            guiGraphics.fill(x, y, x + 2, y + boxH, 0xFF9B5DE5);
        } else if (style == 2) {
            guiGraphics.fill(x, y, x + boxW, y + boxH, 0x7A09090E);
            guiGraphics.fill(x, y, x + boxW, y + 1, 0x609B5DE5);
            guiGraphics.fill(x, y + boxH - 1, x + boxW, y + boxH, 0x609B5DE5);
        }

        int textX = x + padX + (style == 0 ? 2 : 0);
        int textY = y + padY;
        guiGraphics.text(font, linePrimary, textX, textY, 0xFFF2F2F7, true);
        textY += font.lineHeight + 1;

        if (!lineSecondary.isEmpty()) {
            guiGraphics.text(font, lineSecondary, textX, textY, 0xFFC8C2D8, true);
            textY += font.lineHeight + 1;
        }
        if (!lineCoords.isEmpty()) {
            guiGraphics.text(font, lineCoords, textX, textY, 0xFFB8E2DC, true);
            textY += font.lineHeight + 1;
        }

        if (showGraph && validSamples > 0) {
            int gx = textX;
            int gy = textY + graphGap - 1;
            int gw = Math.min(GRAPH_SAMPLES, boxW - padX * 2 - (style == 0 ? 2 : 0));
            guiGraphics.fill(gx, gy, gx + gw, gy + graphH, 0x55000000);

            int start = (sampleCursor - gw + SAMPLE_COUNT) % SAMPLE_COUNT;
            for (int i = 0; i < gw; i++) {
                float ms = FRAME_TIMES_MS[(start + i) % SAMPLE_COUNT];
                if (ms <= 0.0f) continue;
                int barH = Math.max(1, Math.min(graphH, (int) (ms * (graphH / 33.3f))));
                int color = ms <= 8.5f ? 0xFF5CE0A8 : (ms <= 16.7f ? 0xFF9B5DE5 : 0xFFE55D75);
                guiGraphics.fill(gx + i, gy + graphH - barH, gx + i + 1, gy + graphH, color);
            }
        }

        guiGraphics.pose().popMatrix();
    }

    private static void recordFrameTiming() {
        long nowNs = System.nanoTime();
        if (lastFrameNs != 0L) {
            float dtMs = (nowNs - lastFrameNs) / 1_000_000.0f;
            if (dtMs > 0.0f && dtMs < 1000.0f) {
                FRAME_TIMES_MS[sampleCursor] = dtMs;
                sampleCursor = (sampleCursor + 1) % SAMPLE_COUNT;
                if (validSamples < SAMPLE_COUNT) {
                    validSamples++;
                }
            }
        }
        lastFrameNs = nowNs;
    }

    private static void rebuildCachedLines(int mode, Config cfg) {
        int fps = MINECRAFT.getFps();
        float avgMs = 0.0f;
        int onePercentLow = fps;

        if (validSamples > 0) {
            float sum = 0.0f;
            for (int i = 0; i < validSamples; i++) {
                float v = FRAME_TIMES_MS[i];
                SORT_SCRATCH[i] = v;
                sum += v;
            }
            avgMs = sum / validSamples;
            Arrays.sort(SORT_SCRATCH, 0, validSamples);
            int p99Idx = Math.min(validSamples - 1, Math.max(0, (int) (validSamples * 0.99f)));
            float p99Ms = SORT_SCRATCH[p99Idx];
            if (p99Ms > 0.0f) {
                onePercentLow = Math.max(1, Math.round(1000.0f / p99Ms));
            }
        }

        int pingMs = -1;
        if (cfg != null && cfg.hudShowPing && MINECRAFT.player != null && MINECRAFT.getConnection() != null) {
            PlayerInfo info = MINECRAFT.getConnection().getPlayerInfo(MINECRAFT.player.getUUID());
            if (info != null) {
                pingMs = info.getLatency();
            }
        }

        if (mode == 1) {
            linePrimary = pingMs >= 0
                    ? String.format("%d FPS | %d ms", fps, pingMs)
                    : fps + " FPS";
            lineSecondary = "";
        } else {
            linePrimary = String.format("%d FPS (1%%: %d) | %.1f ms", fps, onePercentLow, avgMs);
            MemoryManager mm = MemoryManager.getInstance();
            int usedVram = mm != null ? mm.getAllocatedDeviceMemoryMB() : 0;
            int totalVram = mm != null ? mm.getDeviceMemoryMB() : 0;
            WorldRenderer wr = WorldRenderer.getInstance();
            int visibleChunks = wr != null ? wr.getVisibleSectionsCount() : 0;
            if (pingMs >= 0) {
                lineSecondary = String.format("VRAM %d/%d MB | C: %d | %d ms", usedVram, totalVram, visibleChunks, pingMs);
            } else {
                lineSecondary = String.format("VRAM %d/%d MB | C: %d", usedVram, totalVram, visibleChunks);
            }
        }

        if (cfg != null && cfg.hudShowCoords && MINECRAFT.getCameraEntity() != null) {
            Entity cam = MINECRAFT.getCameraEntity();
            Direction facing = cam.getDirection();
            lineCoords = String.format("XYZ: %.0f / %.0f / %.0f (%s)", cam.getX(), cam.getY(), cam.getZ(), facing.getName().toUpperCase());
        } else {
            lineCoords = "";
        }
    }

    public static boolean shouldRender() {
        return Initializer.CONFIG != null && Initializer.CONFIG.hudMode > 0;
    }
}
