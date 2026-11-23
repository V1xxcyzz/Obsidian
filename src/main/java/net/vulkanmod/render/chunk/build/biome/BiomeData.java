package net.vulkanmod.render.chunk.build.biome;

import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.vulkanmod.render.chunk.build.RenderRegion;

public class BiomeData {
    private static final int ZOOM_LENGTH = 4;
    private static final int BIOMES_PER_SECTION = 4 * 4 * 4;
    private static final int SIZE = RenderRegion.SIZE * BIOMES_PER_SECTION;

    final Biome[] biomes = new Biome[SIZE];
    private final long biomeZoomSeed;

    final int secX, secY, secZ;

    // Flat primitive cache for cell offsets (x, y, z per cell) + validity bitset
    private final float[] offsetCoords = new float[SIZE * 3];
    private final long[] offsetComputed = new long[(SIZE + 63) >> 6];

    public BiomeData(long biomeZoomSeed, int secX, int secY, int secZ) {
        this.biomeZoomSeed = biomeZoomSeed;
        this.secX = secX;
        this.secY = secY;
        this.secZ = secZ;
    }

    public void getBiomeData(Biome defaultValue, LevelChunkSection chunkSection, int secX, int secY, int secZ) {
        int baseIdx = getRelativeSectionIdx(secX, secY, secZ);

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    int relIdx = getRelativeIdx(x, y, z);
                    int idx = baseIdx + relIdx;

                    if (chunkSection != null) {
                        biomes[idx] = chunkSection.getNoiseBiome(x, y, z)
                                                  .value();
                    }
                    else {
                        biomes[idx] = defaultValue;
                    }

                }
            }
        }

    }

    public Biome getBiome(int blockX, int blockY, int blockZ) {
        int x = blockX - 2;
        int y = blockY - 2;
        int z = blockZ - 2;

        int zoomX = x >> 2;
        int zoomY = y >> 2;
        int zoomZ = z >> 2;

        float fracZoomX = (x & 3) * 0.25f;
        float fracZoomY = (y & 3) * 0.25f;
        float fracZoomZ = (z & 3) * 0.25f;

        int closestCellIdx = 0;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (int i = 0; i < 8; ++i) {
            boolean dirX = (i & 4) != 0;
            boolean dirY = (i & 2) != 0;
            boolean dirZ = (i & 1) != 0;

            int cellX = dirX ? zoomX + 1 : zoomX;
            int cellY = dirY ? zoomY + 1 : zoomY;
            int cellZ = dirZ ? zoomZ + 1 : zoomZ;

            float fCellX = dirX ? fracZoomX - 1.0f : fracZoomX;
            float fCellY = dirY ? fracZoomY - 1.0f : fracZoomY;
            float fCellZ = dirZ ? fracZoomZ - 1.0f : fracZoomZ;

            int baseSectionIdx = getSectionIdx(cellX >> 2, cellY >> 2, cellZ >> 2);
            int cellIdx = baseSectionIdx + getRelativeIdx(cellX & 3, cellY & 3, cellZ & 3);

            int coordBase = getOrComputeOffsetBase(cellIdx, cellX, cellY, cellZ);
            float distance = Mth.square(fCellX + this.offsetCoords[coordBase])
                           + Mth.square(fCellY + this.offsetCoords[coordBase + 1])
                           + Mth.square(fCellZ + this.offsetCoords[coordBase + 2]);

            if (closestDistance > distance) {
                closestCellIdx = cellIdx;
                closestDistance = distance;
            }
        }

        return this.biomes[closestCellIdx];
    }

    private int getSectionIdx(int secX, int secY, int secZ) {
        return getRelativeSectionIdx(secX - this.secX, secY - this.secY, secZ - this.secZ);
    }

    private int getOrComputeOffsetBase(int idx, int cellX, int cellY, int cellZ) {
        int wordIdx = idx >>> 6;
        long bit = 1L << (idx & 63);
        int coordBase = idx * 3;
        if ((this.offsetComputed[wordIdx] & bit) == 0L) {
            this.offsetComputed[wordIdx] |= bit;
            computeCellOffset(this.biomeZoomSeed, cellX, cellY, cellZ, this.offsetCoords, coordBase);
        }
        return coordBase;
    }

    private static void computeCellOffset(long l, int cellX, int cellY, int cellZ, float[] out, int outBase) {
        long seed;
        seed = LinearCongruentialGenerator.next(l, cellX);
        seed = LinearCongruentialGenerator.next(seed, cellY);
        seed = LinearCongruentialGenerator.next(seed, cellZ);
        seed = LinearCongruentialGenerator.next(seed, cellX);
        seed = LinearCongruentialGenerator.next(seed, cellY);
        seed = LinearCongruentialGenerator.next(seed, cellZ);

        out[outBase] = getFiddle(seed);
        seed = LinearCongruentialGenerator.next(seed, l);
        out[outBase + 1] = getFiddle(seed);
        seed = LinearCongruentialGenerator.next(seed, l);
        out[outBase + 2] = getFiddle(seed);
    }

    private static float getFiddle(long l) {
        float d = Math.floorMod(l >> 24, 1024) * (1.0f / 1024.0f);
        return (d - 0.5f) * 0.9f;
    }

    private static int getRelativeSectionIdx(int x, int y, int z) {
        return ((x * RenderRegion.WIDTH * RenderRegion.WIDTH) + (y * RenderRegion.WIDTH) + z) * BIOMES_PER_SECTION;
    }

    private static int getRelativeIdx(int x, int y, int z) {
        return (x * ZOOM_LENGTH * ZOOM_LENGTH) + (y * ZOOM_LENGTH) + z;
    }

}
