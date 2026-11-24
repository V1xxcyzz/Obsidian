package net.vulkanmod.config;

public abstract class UpdateChecker {
    private static boolean updateAvailable = false;

    public static void checkForUpdates() {
        // Obsidian builds are distributed standalone; skip upstream VulkanMod update queries.
    }

    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }
}
