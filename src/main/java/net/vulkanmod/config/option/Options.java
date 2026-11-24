package net.vulkanmod.config.option;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.gui.*;
import net.vulkanmod.config.video.*;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.light.LightMode;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public abstract class Options {

    public static boolean fullscreenDirty = false;

    private static final Config config = Initializer.CONFIG;
    private static final Minecraft minecraft = Minecraft.getInstance();
    private static final Window window = minecraft.getWindow();
    private static final net.minecraft.client.Options mcOptions = minecraft.options;

    public static List<OptionPage> getOptionPages() {
        List<OptionPage> optionPages = new ArrayList<>();

        OptionPage page = new OptionPage(
                Component.translatable("obsidian.options.pages.video").getString(),
                Options.getVideoOpts()
        );
        optionPages.add(page);

        page = new OptionPage(
                Component.translatable("obsidian.options.pages.graphics").getString(),
                Options.getGraphicsOpts()
        );
        optionPages.add(page);

        page = new OptionPage(
                Component.translatable("obsidian.options.pages.optimizations").getString(),
                Options.getOptimizationOpts()
        );
        optionPages.add(page);

        page = new OptionPage(
                Component.translatable("obsidian.options.pages.extras").getString(),
                Options.getExtrasOpts()
        );
        optionPages.add(page);

        page = new OptionPage(
                Component.translatable("obsidian.options.pages.other").getString(),
                Options.getOtherOpts()
        );
        optionPages.add(page);

        return optionPages;
    }

    public static OptionBlock[] getVideoOpts() {
        VideoModeManager.checkConfigVideoMode(config);
        VideoModeManager.selectBestMonitor(window);
        var resolutions = VideoModeManager.getVideoResolutions();
        if (resolutions == null || resolutions.length == 0) {
            resolutions = new VideoModeSet[]{VideoModeSet.getDummy()};
        }

        var videoMode = config.videoMode;
        var videoModeSet = VideoModeManager.getVideoModeSet(videoMode);

        if (videoModeSet == null) {
            videoModeSet = resolutions[resolutions.length - 1];
            videoMode = videoModeSet.getVideoMode();
        }

        VideoModeManager.selectedVideoMode = videoMode;
        var refreshRates = videoModeSet.getRefreshRates();

        var windowModeOption = new CyclingOption<>(Component.translatable("obsidian.options.windowMode"),
                                                   WindowMode.values(),
                                                   value -> {
                                                       boolean exclusiveFullscreen = value == WindowMode.EXCLUSIVE_FULLSCREEN;
                                                       mcOptions.fullscreen()
                                                                       .set(exclusiveFullscreen);

                                                       config.windowMode = value.mode;
                                                       fullscreenDirty = true;
                                                   },
                                                   () -> WindowMode.fromValue(config.windowMode))
                .setTranslator(value -> Component.translatable(WindowMode.getComponentName(value)));

        CyclingOption<Integer> refreshRateOption = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("obsidian.options.refreshRate"),
                refreshRates.toArray(new Integer[0]),
                (value) -> {
                    VideoModeManager.selectedVideoMode.refreshRate = value;
                    VideoModeManager.applySelectedVideoMode();

                    if (mcOptions.fullscreen().get()) {
                        fullscreenDirty = true;
                    }
                },
                () -> VideoModeManager.selectedVideoMode.refreshRate)
                .setTranslator(refreshRate -> Component.nullToEmpty(refreshRate.toString()))
                .setActivationFn(() -> windowModeOption.getNewValue() == WindowMode.EXCLUSIVE_FULLSCREEN);

        Option<VideoModeSet> resolutionOption = new CyclingOption<>(
                Component.translatable("options.fullscreen.resolution"),
                resolutions,
                (value) -> {
                    VideoModeManager.selectedVideoMode = value.getVideoMode(refreshRateOption.getNewValue());
                    VideoModeManager.applySelectedVideoMode();

                    if (mcOptions.fullscreen().get()) {
                        fullscreenDirty = true;
                    }
                },
                () -> {
                    var selectedVideoMode = VideoModeManager.selectedVideoMode;
                    var selectedVideoModeSet = VideoModeManager.getVideoModeSet(selectedVideoMode);

                    return selectedVideoModeSet != null ? selectedVideoModeSet : VideoModeSet.getDummy();
                })
                .setTranslator(resolution -> Component.nullToEmpty(resolution.toString()))
                .setActivationFn(() -> windowModeOption.getNewValue() == WindowMode.EXCLUSIVE_FULLSCREEN);

        resolutionOption.setOnChange(() -> {
            VideoModeSet newSet = resolutionOption.getNewValue();
            Integer[] rates = newSet.getRefreshRates().toArray(new Integer[0]);
            refreshRateOption.setValues(rates);
            refreshRateOption.setNewValue(rates[rates.length - 1]);
        });

        windowModeOption.setOnChange(() -> {
            resolutionOption.updateActiveState();
            refreshRateOption.updateActiveState();
        });

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        windowModeOption,
                        resolutionOption,
                        refreshRateOption,
                        new RangeOption(Component.translatable("options.framerateLimit"),
                                10, 260, 10,
                                value -> Component.nullToEmpty(value == 260
                                        ? Component.translatable("options.framerateLimit.max").getString()
                                        : String.valueOf(value)),
                                value -> {
                                    mcOptions.framerateLimit().set(value);
                                    minecraft.getFramerateLimitTracker().setFramerateLimit(value);
                                },
                                () -> mcOptions.framerateLimit().get()),
                        new SwitchOption(Component.translatable("options.vsync"),
                                value -> {
                                    mcOptions.enableVsync().set(value);
                                    Vulkan.setVsync(value);
                                    minecraft.invalidateSurfaceConfiguration();
                                },
                                () -> mcOptions.enableVsync().get()),
                        new CyclingOption<>(Component.translatable("obsidian.options.presentMode"),
                                new Integer[]{0, 1, 2, 3},
                                value -> {
                                    config.presentMode = value;
                                    boolean vsyncOn = value == 3;
                                    mcOptions.enableVsync().set(vsyncOn);
                                    Vulkan.setVsync(vsyncOn);
                                    Renderer.scheduleSwapChainUpdate();
                                    minecraft.invalidateSurfaceConfiguration();
                                },
                                () -> config.presentMode)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.presentMode.mailbox";
                                    case 2 -> "obsidian.options.presentMode.adaptive";
                                    case 3 -> "obsidian.options.presentMode.vsync";
                                    default -> "obsidian.options.presentMode.immediate";
                                }))
                                .setTooltip(v -> Component.translatable("obsidian.options.presentMode.tooltip")),
                        new CyclingOption<>(Component.translatable("options.inactivityFpsLimit"),
                                            InactivityFpsLimit.values(),
                                            value -> mcOptions.inactivityFpsLimit().set(value),
                                            () -> mcOptions.inactivityFpsLimit().get())
                                .setTranslator(InactivityFpsLimit::caption)
                }),
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.guiScale"),
                                0, Math.max(window.calculateScale(0, minecraft.isEnforceUnicode()), mcOptions.guiScale().get()), 1,
                                value -> Component.translatable(value == 0 ? "options.guiScale.auto" : String.valueOf(value)),
                                value -> {
                                    mcOptions.guiScale().set(value);
                                    minecraft.resizeGui();
                                },
                                () -> mcOptions.guiScale().get()),
                        new RangeOption(Component.translatable("options.gamma"),
                                0, 100, 1,
                                value -> Component.translatable(switch (value) {
                                    case 0 -> "options.gamma.min";
                                    case 50 -> "options.gamma.default";
                                    case 100 -> "options.gamma.max";
                                    default -> String.valueOf(value);
                                }),
                                value -> mcOptions.gamma().set(value * 0.01),
                                () -> (int) (mcOptions.gamma().get() * 100.0))
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.attackIndicator"),
                                            AttackIndicatorStatus.values(),
                                            value -> mcOptions.attackIndicator().set(value),
                                            () -> mcOptions.attackIndicator().get())
                                .setTranslator(AttackIndicatorStatus::caption),
                        new SwitchOption(Component.translatable("options.autosaveIndicator"),
                                value -> mcOptions.showAutosaveIndicator().set(value),
                                () -> mcOptions.showAutosaveIndicator().get()),
                        new CyclingOption<>(Component.translatable("obsidian.options.hudMode"),
                                new Integer[]{0, 1, 2},
                                value -> config.hudMode = value,
                                () -> config.hudMode)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.hudMode.fps";
                                    case 2 -> "obsidian.options.hudMode.detailed";
                                    default -> "options.off";
                                }))
                                .setTooltip(v -> Component.translatable("obsidian.options.hudMode.tooltip"))
                })
        };
    }

    public static OptionBlock[] getGraphicsOpts() {
        var texFilteringOption = new CyclingOption<>(Component.translatable("options.textureFiltering"),
                                                     TextureFilteringMethod.values(),
                                                     value -> {
                                                         var oldValue = mcOptions.textureFiltering()
                                                                                        .get();

                                                         if ((oldValue == TextureFilteringMethod.ANISOTROPIC && value != TextureFilteringMethod.ANISOTROPIC)
                                                             || (value == TextureFilteringMethod.ANISOTROPIC && oldValue != TextureFilteringMethod.ANISOTROPIC)) {
                                                             minecraft.delayTextureReload();
                                                             if (WorldRenderer.getInstance() != null) {
                                                                 WorldRenderer.getInstance().resetSampler();
                                                             }
                                                         }

                                                         mcOptions.textureFiltering()
                                                                         .set(value);
                                                     },
                                                     () -> mcOptions.textureFiltering()
                                                                           .get())
                .setTranslator(TextureFilteringMethod::caption)
                .setTooltip(value -> switch (value) {
                    case NONE -> Component.translatable("options.textureFiltering.none.tooltip");
                    case RGSS -> Component.translatable("options.textureFiltering.rgss.tooltip");
                    case ANISOTROPIC -> Component.translatable("options.textureFiltering.anisotropic.tooltip");
                })
                .setImpact(PerformanceImpact.MEDIUM);



        var maxAnisotropyOption = new RangeOption(Component.translatable("options.maxAnisotropy"),
                                                  1, 3, 1,
                                                  value -> {
                                                      var oldValue = mcOptions.maxAnisotropyBit()
                                                                                     .get();

                                                      if (mcOptions.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
                                                          && !oldValue.equals(value)) {
                                                          minecraft.delayTextureReload();
                                                          if (WorldRenderer.getInstance() != null) {
                                                              WorldRenderer.getInstance().resetSampler();
                                                          }
                                                      }

                                                      mcOptions.maxAnisotropyBit()
                                                                      .set(value);
                                                  },
                                                  () -> mcOptions.maxAnisotropyBit()
                                                                        .get())
                .setTranslator((value) -> Component.translatable("options.multiplier", Integer.toString(1 << value)))
                .setTooltip(v -> Component.translatable("options.maxAnisotropy.tooltip"));

        maxAnisotropyOption.setActivationFn(() -> texFilteringOption.getNewValue() == TextureFilteringMethod.ANISOTROPIC);
        texFilteringOption.setOnChange(maxAnisotropyOption::updateActiveState);

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.renderDistance"),
                                2, 32, 1,
                                value -> mcOptions.renderDistance().set(value),
                                () -> mcOptions.renderDistance().get())
                                .setTooltip(v -> Component.literal("Chunk render distance"))
                                .setImpact(PerformanceImpact.HIGH),
                        new RangeOption(Component.translatable("options.simulationDistance"),
                                5, 32, 1,
                                value -> mcOptions.simulationDistance().set(value),
                                () -> mcOptions.simulationDistance().get()),
                        new CyclingOption<>(Component.translatable("options.prioritizeChunkUpdates"),
                                PrioritizeChunkUpdates.values(),
                                value -> mcOptions.prioritizeChunkUpdates().set(value),
                                () -> mcOptions.prioritizeChunkUpdates().get())
                                .setTranslator(PrioritizeChunkUpdates::caption)
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.graphics.preset"),
                                new GraphicsPreset[]{GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.CUSTOM},
                                value -> mcOptions.graphicsPreset().set(value),
                                () -> mcOptions.graphicsPreset().get())
                                .setTranslator(g -> Component.translatable(g.getKey())),
                        texFilteringOption,
                        maxAnisotropyOption,
                        new CyclingOption<>(Component.translatable("options.particles"),
                                            new ParticleStatus[]{ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL},
                                            value -> mcOptions.particles().set(value),
                                            () -> mcOptions.particles().get())
                                .setImpact(PerformanceImpact.MEDIUM)
                                .setTranslator(ParticleStatus::caption),
                        new CyclingOption<>(Component.translatable("options.renderClouds"),
                                            CloudStatus.values(),
                                            value -> mcOptions.cloudStatus().set(value),
                                            () -> mcOptions.cloudStatus().get())
                                .setTranslator(CloudStatus::caption),
                        new RangeOption(Component.translatable("options.renderCloudsDistance"),
                                        2, 128, 1,
                                        value -> mcOptions.cloudRange().set(value),
                                        () -> mcOptions.cloudRange().get()),
                        new SwitchOption(Component.translatable("options.cutoutLeaves"),
                                         value -> mcOptions.cutoutLeaves().set(value),
                                         () -> mcOptions.cutoutLeaves().get())
                                .setTooltip(value -> Component.translatable("options.cutoutLeaves.tooltip")),
                        new RangeOption(Component.translatable("options.chunkFade"),
                                        0, 40, 1,
                                        (value) -> mcOptions.chunkSectionFadeInTime().set(value / 20.0),
                                        () -> (int) (mcOptions.chunkSectionFadeInTime().get() * 20))
                                .setTranslator(value -> Component.literal(String.valueOf(value / 20.0f)))
                                .setTooltip(v -> Component.translatable("options.chunkFade.tooltip")),
                        // TODO: improved transparency
//                        new SwitchOption(Component.translatable("options.improvedTransparency"),
//                                         value -> minecraftOptions.improvedTransparency().set(value),
//                                         () -> minecraftOptions.improvedTransparency().get())
//                                .setTooltip(Component.translatable("options.improvedTransparency.tooltip")),
                        new CyclingOption<>(Component.translatable("options.ao"),
                                new Integer[]{LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK},
                                value -> {
                                    mcOptions.ambientOcclusion().set(value > LightMode.FLAT);
                                    config.ambientOcclusion = value;
                                    if (WorldRenderer.getInstance() != null) WorldRenderer.getInstance().allChanged();
                                },
                                () -> config.ambientOcclusion)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case LightMode.FLAT -> "options.off";
                                    case LightMode.SMOOTH -> "options.on";
                                    case LightMode.SUB_BLOCK -> "obsidian.options.ao.subBlock";
                                    default -> "obsidian.options.unknown";
                                }))
                                .setTooltip(value -> value == LightMode.SUB_BLOCK
                                ? Component.translatable("obsidian.options.ao.subBlock.tooltip")
                                : Component.empty())
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.biomeBlendRadius"),
                                0, 7, 1,
                                value -> Component.nullToEmpty("%d x %d".formatted(value * 2 + 1, value * 2 + 1)),
                                value -> {
                                    mcOptions.biomeBlendRadius().set(value);
                                    if (WorldRenderer.getInstance() != null) WorldRenderer.getInstance().allChanged();
                                },
                                () -> mcOptions.biomeBlendRadius().get())
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.entityShadows"),
                                value -> mcOptions.entityShadows().set(value),
                                () -> mcOptions.entityShadows().get())
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.entityDistanceScaling"),
                                        2, 20, 1,
                                        value -> mcOptions.entityDistanceScaling().set(value / 4.0),
                                        () -> (int) (mcOptions.entityDistanceScaling().get() * 4.0))
                                        .setImpact(PerformanceImpact.HIGH)
                                        .setTranslator(value -> Component.literal(String.valueOf(value / 4.0))),
                        new CyclingOption<>(Component.translatable("options.mipmapLevels"),
                                new Integer[]{0,1,2,3,4},
                                value -> {
                                    mcOptions.mipmapLevels().set(value);
                                    minecraft.updateMaxMipLevel(value);
                                    minecraft.delayTextureReload();
                                },
                                () -> mcOptions.mipmapLevels().get())
                                .setTranslator(v -> Component.literal(String.valueOf(v)))
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.weatherRadius"),
                                        3, 10, 1,
                                        value -> mcOptions.weatherRadius().set(value),
                                        () -> mcOptions.weatherRadius().get())
                                .setTooltip(value -> Component.translatable("options.weatherRadius.tooltip")),
                        new SwitchOption(Component.translatable("options.vignette"),
                                         value -> mcOptions.vignette().set(value),
                                         () -> mcOptions.vignette().get())
                                .setTooltip(value -> Component.translatable("options.vignette.tooltip")),
                })
        };
    }

    public static OptionBlock[] getOptimizationOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("obsidian.options.advCulling"),
                                new Integer[]{1, 2, 3, 10},
                                value -> config.advCulling = value,
                                () -> config.advCulling)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.advCulling.aggressive";
                                    case 2 -> "obsidian.options.advCulling.normal";
                                    case 3 -> "obsidian.options.advCulling.conservative";
                                    case 10 -> "options.off";
                                    default -> "obsidian.options.unknown";
                                }))
                                .setTooltip(v -> v <= 3 ? Component.translatable("obsidian.options.advCulling.tooltip") : Component.empty())
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("obsidian.options.entityCulling"),
                                v -> config.entityCulling = v,
                                () -> config.entityCulling)
                                .setTooltip(v -> Component.translatable("obsidian.options.entityCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("obsidian.options.blockEntityCulling"),
                                v -> config.blockEntityCulling = v,
                                () -> config.blockEntityCulling)
                                .setTooltip(v -> Component.translatable("obsidian.options.blockEntityCulling.tooltip"))
                                .setImpact(PerformanceImpact.MEDIUM),
                        new SwitchOption(Component.translatable("obsidian.options.uniqueOpaqueLayer"),
                                v -> {
                                    config.uniqueOpaqueLayer = v;
                                    TerrainRenderType.updateMapping();
                                    if (WorldRenderer.getInstance() != null) WorldRenderer.getInstance().allChanged();
                                },
                                () -> config.uniqueOpaqueLayer)
                                .setTooltip(v -> Component.translatable("obsidian.options.uniqueOpaqueLayer.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("obsidian.options.backfaceCulling"),
                                v -> {
                                    config.backFaceCulling = v;
                                    if (WorldRenderer.getInstance() != null) WorldRenderer.getInstance().allChanged();
                                },
                                () -> config.backFaceCulling)
                                .setTooltip(v -> Component.translatable("obsidian.options.backfaceCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("obsidian.options.indirectDraw"),
                                v -> config.indirectDraw = v,
                                () -> config.indirectDraw)
                                .setTooltip(v -> Component.translatable("obsidian.options.indirectDraw.tooltip"))
                                .setImpact(PerformanceImpact.HIGH)
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("obsidian.options.asyncChunkUploads"),
                                v -> config.asyncChunkUploads = v,
                                () -> config.asyncChunkUploads)
                                .setTooltip(v -> Component.translatable("obsidian.options.asyncChunkUploads.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new CyclingOption<>(Component.translatable("obsidian.options.chunkUploadBudget"),
                                new Integer[]{0, 1, 2, 4, 8},
                                value -> config.chunkUploadBudgetMs = value,
                                () -> config.chunkUploadBudgetMs)
                                .setTranslator(v -> v == 0
                                        ? Component.translatable("obsidian.options.chunkUploadBudget.unlimited")
                                        : Component.literal(v + " ms"))
                                .setTooltip(v -> Component.translatable("obsidian.options.chunkUploadBudget.tooltip"))
                                .setImpact(PerformanceImpact.MEDIUM),
                        new SwitchOption(Component.translatable("obsidian.options.fastTerrainSampling"),
                                v -> {
                                    config.fastTerrainSampling = v;
                                    if (WorldRenderer.getInstance() != null) {
                                        WorldRenderer.getInstance().resetSampler();
                                    }
                                },
                                () -> config.fastTerrainSampling)
                                .setTooltip(v -> Component.translatable("obsidian.options.fastTerrainSampling.tooltip"))
                                .setImpact(PerformanceImpact.MEDIUM),
                        new SwitchOption(Component.translatable("obsidian.options.animateOnlyVisibleTextures"),
                                v -> config.animateOnlyVisibleTextures = v,
                                () -> config.animateOnlyVisibleTextures)
                                .setTooltip(v -> Component.translatable("obsidian.options.animateOnlyVisibleTextures.tooltip"))
                                .setImpact(PerformanceImpact.MEDIUM)
                })
        };
    }

    public static OptionBlock[] getOtherOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("obsidian.options.builderThreads"),
                                0, Runtime.getRuntime().availableProcessors() - 1, 1,
                                value -> {
                                    config.builderThreads = value;
                                    if (WorldRenderer.getInstance() != null) {
                                        WorldRenderer.getInstance().getTaskDispatcher().createThreads(value);
                                    }
                                },
                                () -> config.builderThreads)
                                .setTranslator(v -> v == 0
                                ? Component.translatable("obsidian.options.builderThreads.auto")
                                : Component.literal(String.valueOf(v))),
                        new RangeOption(Component.translatable("obsidian.options.frameQueue"),
                                2, 5, 1,
                                value -> {
                                    config.frameQueueSize = value;
                                    Renderer.scheduleSwapChainUpdate();
                                },
                                () -> config.frameQueueSize)
                                .setTooltip(v -> Component.translatable("obsidian.options.frameQueue.tooltip")),
                        new SwitchOption(Component.translatable("obsidian.options.textureAnimations"),
                                v -> config.textureAnimations = v,
                                () -> config.textureAnimations),
                        new SwitchOption(Component.translatable("obsidian.options.persistentPipelineCache"),
                                v -> config.persistentPipelineCache = v,
                                () -> config.persistentPipelineCache)
                                .setTooltip(v -> Component.translatable("obsidian.options.persistentPipelineCache.tooltip"))
                                .setImpact(PerformanceImpact.LOW)
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("obsidian.options.wayland"),
                                         v -> config.useWayland = v,
                                         () -> config.useWayland)
                                .setActivationFn(Platform::isLinux)
                                .setTooltip(v -> Component.translatable("obsidian.options.wayland.tooltip")),
                        new CyclingOption<>(Component.translatable("obsidian.options.deviceSelector"),
                                IntStream.range(-1, DeviceManager.suitableDevices.size())
                                        .boxed()
                                        .toArray(Integer[]::new),
                                value -> config.device = value,
                                () -> config.device)
                                .setTranslator(v -> Component.translatable(
                                        v == -1 ? "obsidian.options.deviceSelector.auto"
                                                : DeviceManager.suitableDevices.get(v).deviceName))
                                .setTooltip(v -> Component.literal(
                                Component.translatable("obsidian.options.deviceSelector.tooltip").getString() + ": " +
                                        DeviceManager.device.deviceName))
                })
        };
    }

    public static OptionBlock[] getExtrasOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("obsidian.options.hudMode"),
                                new Integer[]{0, 1, 2},
                                value -> config.hudMode = value,
                                () -> config.hudMode)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.hudMode.fps";
                                    case 2 -> "obsidian.options.hudMode.detailed";
                                    default -> "options.off";
                                }))
                                .setTooltip(v -> Component.translatable("obsidian.options.hudMode.tooltip")),
                        new CyclingOption<>(Component.translatable("obsidian.options.hudPosition"),
                                new Integer[]{0, 1, 2, 3},
                                value -> config.hudPosition = value,
                                () -> config.hudPosition)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.hudPosition.topRight";
                                    case 2 -> "obsidian.options.hudPosition.bottomLeft";
                                    case 3 -> "obsidian.options.hudPosition.bottomRight";
                                    default -> "obsidian.options.hudPosition.topLeft";
                                })),
                        new CyclingOption<>(Component.translatable("obsidian.options.hudStyle"),
                                new Integer[]{0, 1, 2},
                                value -> config.hudStyle = value,
                                () -> config.hudStyle)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.hudStyle.minimal";
                                    case 2 -> "obsidian.options.hudStyle.pill";
                                    default -> "obsidian.options.hudStyle.card";
                                })),
                        new CyclingOption<>(Component.translatable("obsidian.options.hudScale"),
                                new Integer[]{75, 100, 125},
                                value -> config.hudScale = value,
                                () -> config.hudScale)
                                .setTranslator(v -> Component.literal(v + "%")),
                        new SwitchOption(Component.translatable("obsidian.options.hudShowCoords"),
                                v -> config.hudShowCoords = v,
                                () -> config.hudShowCoords),
                        new SwitchOption(Component.translatable("obsidian.options.hudShowPing"),
                                v -> config.hudShowPing = v,
                                () -> config.hudShowPing),
                        new SwitchOption(Component.translatable("obsidian.options.hudShowGraph"),
                                v -> config.hudShowGraph = v,
                                () -> config.hudShowGraph)
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("obsidian.options.environmentalFog"),
                                v -> config.environmentalFog = v,
                                () -> config.environmentalFog)
                                .setTooltip(v -> Component.translatable("obsidian.options.environmentalFog.tooltip")),
                        new CyclingOption<>(Component.translatable("obsidian.options.skyRenderMode"),
                                new Integer[]{0, 1, 2},
                                value -> config.skyRenderMode = value,
                                () -> config.skyRenderMode)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "obsidian.options.skyRenderMode.noStars";
                                    case 2 -> "options.off";
                                    default -> "options.on";
                                }))
                                .setTooltip(v -> Component.translatable("obsidian.options.skyRenderMode.tooltip"))
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("obsidian.options.particleCulling"),
                                v -> config.particleCulling = v,
                                () -> config.particleCulling)
                                .setTooltip(v -> Component.translatable("obsidian.options.particleCulling.tooltip"))
                                .setImpact(PerformanceImpact.MEDIUM),
                        new RangeOption(Component.translatable("obsidian.options.maxParticleDistance"),
                                8, 64, 4,
                                value -> config.maxParticleDistance = value,
                                () -> config.maxParticleDistance)
                                .setTranslator(v -> v >= 64
                                        ? Component.translatable("obsidian.options.maxParticleDistance.unlimited")
                                        : Component.literal(v + " blocks"))
                                .setTooltip(v -> Component.translatable("obsidian.options.maxParticleDistance.tooltip"))
                                .setImpact(PerformanceImpact.MEDIUM)
                })
        };
    }
}