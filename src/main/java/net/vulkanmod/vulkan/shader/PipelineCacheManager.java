package net.vulkanmod.vulkan.shader;

import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Persists Vulkan's VkPipelineCache to disk validated against GPU vendor, device,
 * driver version, and pipelineCacheUUID to eliminate pipeline compilation hitches
 * across launches.
 */
public final class PipelineCacheManager {
    private static final int MAGIC = 0x4F425043; // "OBPC"
    private static final int FORMAT_VERSION = 1;
    private static final int HEADER_SIZE = (5 * Integer.BYTES) + VK_UUID_SIZE;
    private static final String CACHE_FILE_NAME = "obsidian_pipeline_cache.bin";

    private PipelineCacheManager() {
    }

    private static Path getCachePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CACHE_FILE_NAME);
    }

    private static byte[] getDeviceUuid(VkPhysicalDeviceProperties props) {
        ByteBuffer uuidBuf = props.pipelineCacheUUID();
        byte[] uuid = new byte[VK_UUID_SIZE];
        for (int i = 0; i < VK_UUID_SIZE; i++) {
            uuid[i] = uuidBuf.get(i);
        }
        return uuid;
    }

    public static long createPipelineCache(VkDevice device) {
        ByteBuffer initialData = null;

        if (Initializer.CONFIG != null && Initializer.CONFIG.persistentPipelineCache) {
            initialData = loadValidatedCacheData();
        }

        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.calloc(stack);
            cacheCreateInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (initialData != null && initialData.remaining() > 0) {
                cacheCreateInfo.pInitialData(initialData);
            }

            LongBuffer pPipelineCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(device, cacheCreateInfo, null, pPipelineCache);

            // If driver rejected cached data for any reason, retry cleanly with an empty cache
            if (result != VK_SUCCESS && initialData != null) {
                Initializer.LOGGER.warn("Driver rejected cached VkPipelineCache (code {}); starting with empty cache", result);
                cacheCreateInfo.pInitialData(null);
                result = vkCreatePipelineCache(device, cacheCreateInfo, null, pPipelineCache);
            }

            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create graphics pipeline cache: " + result);
            }

            return pPipelineCache.get(0);
        } finally {
            if (initialData != null) {
                MemoryUtil.memFree(initialData);
            }
        }
    }

    public static void saveAndDestroy(VkDevice device, long pipelineCache) {
        if (pipelineCache == VK_NULL_HANDLE) {
            return;
        }

        try {
            if (Initializer.CONFIG != null && Initializer.CONFIG.persistentPipelineCache) {
                saveCacheData(device, pipelineCache);
            }
        } catch (Exception e) {
            Initializer.LOGGER.warn("Failed to save VkPipelineCache to disk", e);
        } finally {
            vkDestroyPipelineCache(device, pipelineCache, null);
        }
    }

    private static ByteBuffer loadValidatedCacheData() {
        Path cachePath = getCachePath();
        if (!Files.isRegularFile(cachePath)) {
            return null;
        }

        try {
            byte[] allBytes = Files.readAllBytes(cachePath);
            if (allBytes.length <= HEADER_SIZE) {
                return null;
            }

            ByteBuffer header = ByteBuffer.wrap(allBytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            int magic = header.getInt();
            int version = header.getInt();
            int vendorId = header.getInt();
            int deviceId = header.getInt();
            int driverVersion = header.getInt();
            byte[] fileUuid = new byte[VK_UUID_SIZE];
            header.get(fileUuid);

            VkPhysicalDeviceProperties props = DeviceManager.deviceProperties();
            if (magic != MAGIC
                    || version != FORMAT_VERSION
                    || vendorId != props.vendorID()
                    || deviceId != props.deviceID()
                    || driverVersion != props.driverVersion()
                    || !Arrays.equals(fileUuid, getDeviceUuid(props))) {
                Initializer.LOGGER.info("Pipeline cache header mismatch (GPU or driver changed); rebuilding cache");
                return null;
            }

            int payloadSize = allBytes.length - HEADER_SIZE;
            ByteBuffer directData = MemoryUtil.memAlloc(payloadSize);
            directData.put(allBytes, HEADER_SIZE, payloadSize);
            directData.flip();

            Initializer.LOGGER.info("Loaded VkPipelineCache ({} KB) from disk", payloadSize / 1024);
            return directData;
        } catch (IOException e) {
            Initializer.LOGGER.warn("Could not read VkPipelineCache from disk; starting fresh", e);
            return null;
        }
    }

    private static void saveCacheData(VkDevice device, long pipelineCache) throws IOException {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pDataSize = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, pipelineCache, pDataSize, null) != VK_SUCCESS) {
                return;
            }

            long sizeLong = pDataSize.get(0);
            if (sizeLong <= 0 || sizeLong > 128L * 1024L * 1024L) {
                return;
            }

            int payloadSize = (int) sizeLong;
            ByteBuffer payload = MemoryUtil.memAlloc(payloadSize);
            try {
                if (vkGetPipelineCacheData(device, pipelineCache, pDataSize, payload) != VK_SUCCESS) {
                    return;
                }

                int actualSize = (int) Math.min(payloadSize, pDataSize.get(0));
                VkPhysicalDeviceProperties props = DeviceManager.deviceProperties();

                byte[] output = new byte[HEADER_SIZE + actualSize];
                ByteBuffer header = ByteBuffer.wrap(output, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                header.putInt(MAGIC);
                header.putInt(FORMAT_VERSION);
                header.putInt(props.vendorID());
                header.putInt(props.deviceID());
                header.putInt(props.driverVersion());
                header.put(getDeviceUuid(props));

                payload.limit(actualSize);
                payload.position(0);
                payload.get(output, HEADER_SIZE, actualSize);

                Path cachePath = getCachePath();
                Path parent = cachePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Path tempPath = cachePath.resolveSibling(CACHE_FILE_NAME + ".tmp");
                Files.write(tempPath, output);
                Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);

                Initializer.LOGGER.info("Saved VkPipelineCache ({} KB) to disk", actualSize / 1024);
            } finally {
                MemoryUtil.memFree(payload);
            }
        }
    }
}
