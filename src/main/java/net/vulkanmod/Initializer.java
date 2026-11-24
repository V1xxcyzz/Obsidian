package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Obsidian");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {
		VERSION = FabricLoader.getInstance()
				.getModContainer("obsidian")
				.or(() -> FabricLoader.getInstance().getModContainer("vulkanmod"))
				.orElseThrow()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== Obsidian {} ==", VERSION);

		var configDir = FabricLoader.getInstance().getConfigDir();
		var configPath = configDir.resolve("obsidian_settings.json");
		var legacyPath = configDir.resolve("vulkanmod_settings.json");
		if (!Files.exists(configPath) && Files.exists(legacyPath)) {
			try {
				Files.copy(legacyPath, configPath);
			} catch (IOException e) {
				LOGGER.warn("Failed to migrate legacy VulkanMod settings from {}", legacyPath, e);
			}
		}

		CONFIG = loadConfig(configPath);
		Platform.init();

		Renderer.register(VulkanModRenderer.INSTANCE);

		UpdateChecker.checkForUpdates();
	}

	private static Config loadConfig(Path path) {
        return Config.load(path);
	}

	public static String getVersion() {
		return VERSION;
	}
}
