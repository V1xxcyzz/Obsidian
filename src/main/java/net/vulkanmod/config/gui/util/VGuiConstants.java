package net.vulkanmod.config.gui.util;

import net.vulkanmod.vulkan.util.ColorUtil;

public class VGuiConstants {
    public static final int COLOR_WHITE = ColorUtil.ARGB.pack(1f, 1f, 1f, 1f);
    public static final int COLOR_BLACK = ColorUtil.ARGB.pack(0f, 0f, 0f, 1f);
    public static final int COLOR_GRAY = ColorUtil.ARGB.pack(0.62f, 0.62f, 0.66f, 1f);

    // Obsidian Amethyst accent (kept as COLOR_RED alias for third-party mod settings compatibility)
    public static final int COLOR_ACCENT = ColorUtil.ARGB.pack(0.61f, 0.36f, 0.90f, 1.0f);
    public static final int COLOR_RED = COLOR_ACCENT;

    // Sodium-inspired translucent dark surfaces
    public static final int COLOR_PANEL_BG = ColorUtil.ARGB.pack(0.04f, 0.04f, 0.06f, 0.72f);
    public static final int COLOR_PANEL_BG_DISABLED = ColorUtil.ARGB.pack(0.04f, 0.04f, 0.06f, 0.45f);
    public static final int COLOR_TOOLTIP_BG = ColorUtil.ARGB.pack(0.05f, 0.05f, 0.07f, 0.88f);
    public static final int COLOR_BORDER_SUBTLE = ColorUtil.ARGB.pack(1f, 1f, 1f, 0.12f);

    public static final int PAGE_BUTTON_WIDTH = 100;
    public static final int WIDGET_HEIGHT = 20;
    public static final int WIDGET_MARGIN = 5;

}
