package net.vulkanmod.config;

import net.minecraft.util.Util;
import org.apache.commons.lang3.SystemUtils;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVersion;

import static net.vulkanmod.Initializer.CONFIG;
import static net.vulkanmod.Initializer.LOGGER;

public abstract class Platform {
    public static final int PLAT_ANY = 0;
    public static final int PLAT_WIN32 = 1;
    public static final int PLAT_COCOA = 2;
    public static final int PLAT_WAYLAND = 3;
    public static final int PLAT_X11 = 4;

    public static final Util.OS OS = Util.getPlatform();
    private static int activePlat;
    private static String activeDE;

    public static void init() {
        activePlat = getSupportedPlat();
        activeDE = determineDE();

        if (activePlat == PLAT_WAYLAND) {
            SDLHints.SDL_SetHint("SDL_VIDEO_DRIVER", "wayland,x11");
        } else if (activePlat == PLAT_X11) {
            SDLHints.SDL_SetHint("SDL_VIDEO_DRIVER", "x11");
        }
        LOGGER.info("Selecting Platform: {}", getStringFromPlat());
        LOGGER.info("SDL: {}", SDLVersion.SDL_GetVersion());
    }

    private static int determineLinuxDisplayServer() {
        String xdgSessionType = System.getenv("XDG_SESSION_TYPE");
        if (xdgSessionType == null) return PLAT_ANY;
        return switch (xdgSessionType) {
            case "wayland" -> CONFIG.useWayland ? PLAT_WAYLAND : PLAT_X11;
            case "x11" -> PLAT_X11;
            default -> PLAT_ANY;
        };
    }

    private static int getSupportedPlat() {
        if (SystemUtils.IS_OS_WINDOWS) return PLAT_WIN32;
        if (SystemUtils.IS_OS_MAC_OSX) return PLAT_COCOA;
        if (SystemUtils.IS_OS_LINUX) return determineLinuxDisplayServer();

        return PLAT_ANY;
    }

    private static String getStringFromPlat() {
        return switch (Platform.activePlat) {
            case PLAT_WIN32 -> "WIN32";
            case PLAT_WAYLAND -> "WAYLAND";
            case PLAT_X11 -> "X11";
            case PLAT_COCOA -> "MACOS";
            case PLAT_ANY -> "ANDROID";
            default -> throw new IllegalStateException("Unexpected value: " + Platform.activePlat);
        };
    }

    private static String determineDE() {
        String xdgSessionDesktop = System.getenv("XDG_SESSION_DESKTOP");
        String xdgCurrentDesktop = System.getenv("XDG_CURRENT_DESKTOP");
        if (xdgSessionDesktop != null)
            return xdgSessionDesktop.toLowerCase();
        if (xdgCurrentDesktop != null)
            return xdgCurrentDesktop.toLowerCase();
        return "N/A";
    }

    public static boolean isWindows() {
        return OS == Util.OS.WINDOWS;
    }

    public static boolean isLinux() {
        return OS == Util.OS.LINUX;
    }

    public static boolean isMacOS() {
        return OS == Util.OS.OSX;
    }

    public static int getActivePlat() {
        return activePlat;
    }

    public static boolean isAndroid() {
        return activePlat == PLAT_ANY;
    }

    public static boolean isWayLand() {
        return activePlat == PLAT_WAYLAND;
    }

    public static boolean isX11() {
        return activePlat == PLAT_X11;
    }

    public static boolean isGnome() {
        return activeDE.contains("gnome");
    }

    public static boolean isWeston() {
        return activeDE.contains("weston");
    }

    public static boolean isGeneric() {
        return activeDE.contains("generic");
    }
}
