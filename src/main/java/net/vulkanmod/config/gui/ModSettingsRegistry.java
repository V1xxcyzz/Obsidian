package net.vulkanmod.config.gui;

import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.api.VkModSettingsEntryBuilder;
import net.vulkanmod.config.api.VkModSettingsFactory;
import net.vulkanmod.config.option.Options;

import java.util.List;
import java.util.Set;

public class ModSettingsRegistry {
    private static final String CONFIG_ENTRY_POINT_KEY = "obsidian:mod_settings_registry";
    private static final String LEGACY_CONFIG_ENTRY_POINT_KEY = "vulkanmod:mod_settings_registry";

    public static final ModSettingsRegistry INSTANCE = new ModSettingsRegistry();

    private final Set<ModSettingsEntry> modEntries = new ObjectArraySet<>();

    ModSettingsRegistry() {
        ModSettingsEntry obsidianSettings = new ModSettingsEntry(Component.literal("Obsidian").withStyle(ChatFormatting.DARK_PURPLE),
                                                                 () -> Identifier.fromNamespaceAndPath("obsidian", "icon.png"),
                                                                 Options::getOptionPages,
                                                                 () -> Initializer.CONFIG.write());
        this.addModEntry(obsidianSettings);

        // build and add obsidian & legacy vulkanmod settings entrypoints
        for (String entrypointKey : List.of(CONFIG_ENTRY_POINT_KEY, LEGACY_CONFIG_ENTRY_POINT_KEY)) {
            var entryPointContainers = FabricLoader.getInstance().getEntrypointContainers(entrypointKey, VkModSettingsFactory.class);
            for (EntrypointContainer<VkModSettingsFactory> entryPointContainer : entryPointContainers) {
                VkModSettingsFactory modSettingsFactory = entryPointContainer.getEntrypoint();
                VkModSettingsEntryBuilder builder = new VkModSettingsEntryBuilder();

                this.addModEntry(modSettingsFactory.build(builder));
            }
        }
    }

    public void addModEntry(ModSettingsEntry entry) {
        this.modEntries.add(entry);
    }

    public Set<ModSettingsEntry> getModEntries() {
        return modEntries;
    }
}
