package net.vulkanmod.config.video;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import org.lwjgl.sdl.SDLVideo;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.clamp;

public abstract class VideoModeManager {
    public static Int2ObjectMap<Monitor> monitors;
    public static Int2ObjectMap<VideoModeSet[]> monitorToVideoModeSets = new Int2ObjectOpenHashMap<>();
    public static Int2ObjectMap<VideoModeSet.VideoMode> osVideoModes = new Int2ObjectOpenHashMap<>();

    public static int selectedMonitor;
    public static VideoModeSet.VideoMode selectedVideoMode;

    public static void init(Int2ObjectMap<Monitor> monitors) {
        VideoModeManager.monitors = monitors;
        monitorToVideoModeSets.clear();
        osVideoModes.clear();

        for (int monitorId : VideoModeManager.monitors.keySet()) {
            addMonitorVideoModes(monitorId);
        }
    }

    public static void addMonitorVideoModes(int monitorId) {
        Monitor monitor = monitors != null ? monitors.get(monitorId) : null;
        if (monitor == null) {
            monitor = Monitor.tryCreate(monitorId);
        }
        if (monitor == null) {
            return;
        }
        monitorToVideoModeSets.put(monitorId, getVideoResolutions(monitor));
        osVideoModes.put(monitorId, getCurrentVideoMode(monitor));
    }

    public static void removeMonitor(int monitorId) {
        monitorToVideoModeSets.remove(monitorId);
        osVideoModes.remove(monitorId);
    }

    public static void applySelectedVideoMode() {
        Initializer.CONFIG.videoMode = selectedVideoMode;
    }

    public static VideoModeSet[] getVideoResolutions() {
        return monitorToVideoModeSets.get(selectedMonitor);
    }

    public static VideoModeSet getFirstAvailable() {
        var videoModeSets = monitorToVideoModeSets.get(SDLVideo.SDL_GetPrimaryDisplay());
        if (videoModeSets == null && !monitorToVideoModeSets.isEmpty()) {
            videoModeSets = monitorToVideoModeSets.values().iterator().next();
        }

        if (videoModeSets != null && videoModeSets.length > 0)
            return videoModeSets[videoModeSets.length - 1];
        else
            return null;
    }

    public static VideoModeSet.VideoMode getOsVideoMode() {
        return osVideoModes.get(selectedMonitor);
    }

    public static VideoModeSet.VideoMode getCurrentVideoMode(int monitorId) {
        Monitor monitor = monitors != null ? monitors.get(monitorId) : null;
        if (monitor == null) {
            monitor = Monitor.tryCreate(monitorId);
        }
        if (monitor == null) {
            throw new NullPointerException("Unable to get current video mode for display " + monitorId);
        }
        return getCurrentVideoMode(monitor);
    }

    private static VideoModeSet.VideoMode getCurrentVideoMode(Monitor monitor) {
        VideoMode vidMode = monitor.currentMode();
        return new VideoModeSet.VideoMode(
                vidMode.getWidth(),
                vidMode.getHeight(),
                vidMode.getRedBits(),
                Math.round(vidMode.getRefreshRate())
        );
    }

    public static VideoModeSet[] getVideoResolutions(int monitorId) {
        Monitor monitor = monitors != null ? monitors.get(monitorId) : null;
        if (monitor == null) {
            monitor = Monitor.tryCreate(monitorId);
        }
        if (monitor == null) {
            return new VideoModeSet[0];
        }
        return getVideoResolutions(monitor);
    }

    private static VideoModeSet[] getVideoResolutions(Monitor monitor) {
        List<VideoMode> modes = monitor.videoModes();
        List<VideoModeSet> videoModeSets = new ArrayList<>();

        int currWidth = 0, currHeight = 0, currBitDepth = 0;
        VideoModeSet videoModeSet = null;

        for (VideoMode mode : modes) {
            int bitDepth = mode.getRedBits();
            if (bitDepth < 8 || mode.getGreenBits() != bitDepth || mode.getBlueBits() != bitDepth)
                continue;

            int width = mode.getWidth();
            int height = mode.getHeight();
            int refreshRate = Math.round(mode.getRefreshRate());

            if (currWidth != width || currHeight != height || currBitDepth != bitDepth) {
                currWidth = width;
                currHeight = height;
                currBitDepth = bitDepth;

                videoModeSet = new VideoModeSet(currWidth, currHeight, currBitDepth);
                videoModeSets.add(videoModeSet);
            }

            if (videoModeSet != null && !videoModeSet.hasRefreshRate(refreshRate)) {
                videoModeSet.addRefreshRate(refreshRate);
            }
        }

        return videoModeSets.toArray(new VideoModeSet[0]);
    }

    public static VideoModeSet getVideoModeSet(VideoModeSet.VideoMode videoMode) {
        var videoModeSets = monitorToVideoModeSets.get(selectedMonitor);
        if (videoModeSets == null) {
            return null;
        }
        for (var set : videoModeSets) {
            if (set.width == videoMode.width && set.height == videoMode.height)
                return set;
        }

        return null;
    }

    public static void selectBestMonitor(Window window) {
        Monitor best = findBestMonitor(window);
        selectedMonitor = best != null ? best.id() : SDLVideo.SDL_GetPrimaryDisplay();
    }

    public static void checkConfigVideoMode(Config config) {
        var videoMode = config.videoMode;
        if (videoMode.width <= 0 || videoMode.height <= 0 || videoMode.refreshRate <= 0) {
            var firstAvailable = getFirstAvailable();
            if (firstAvailable != null) {
                config.videoMode = firstAvailable.getVideoMode();
                config.write();
            }
        }
    }

    public static Monitor findBestMonitor(final Window window) {
        if (monitors == null || monitors.isEmpty()) {
            return null;
        }
        int windowMonitor = window.handle() != 0L ? SDLVideo.SDL_GetDisplayForWindow(window.handle()) : 0;
        if (windowMonitor != 0 && monitors.containsKey(windowMonitor)) {
            return monitors.get(windowMonitor);
        }

        int winMinX = window.getX();
        int winMaxX = winMinX + window.getScreenWidth();
        int winMinY = window.getY();
        int winMaxY = winMinY + window.getScreenHeight();
        int maxArea = -1;
        Monitor result = null;
        int primaryMonitor = SDLVideo.SDL_GetPrimaryDisplay();
        Initializer.LOGGER.debug("Selecting monitor - primary: {}, current monitors: {}", primaryMonitor, monitors);

        for (Monitor monitor : monitors.values()) {
            int monMinX = monitor.x();
            int monMaxX = monMinX + monitor.currentMode().getWidth();
            int monMinY = monitor.y();
            int monMaxY = monMinY + monitor.currentMode().getHeight();
            int minX = clamp(winMinX, monMinX, monMaxX);
            int maxX = clamp(winMaxX, monMinX, monMaxX);
            int minY = clamp(winMinY, monMinY, monMaxY);
            int maxY = clamp(winMaxY, monMinY, monMaxY);
            int sx = Math.max(0, maxX - minX);
            int sy = Math.max(0, maxY - minY);
            int area = sx * sy;
            if (area > maxArea) {
                result = monitor;
                maxArea = area;
            } else if (area == maxArea && primaryMonitor == monitor.id()) {
                Initializer.LOGGER.debug("Primary monitor {} is preferred to monitor {}", monitor, result);
                result = monitor;
            }
        }

        Initializer.LOGGER.debug("Selected monitor: {}", result);
        return result;
    }

    public static Int2ObjectMap<Monitor> getMonitors() {
        return monitors;
    }
}
