package net.vulkanmod.render.profiling;

import net.vulkanmod.render.chunk.WorldRenderer;

public abstract class BuildTimeProfiler {

    private static boolean bench = false;
    private static long startTime;
    private static float deltaTime;

    public static void runBench(boolean building) {
        if(bench) {
            if (startTime == 0) {
                startTime = System.nanoTime();
            }

            if(!building) {
                deltaTime = (System.nanoTime() - startTime) * 0.000001f;
                bench = false;
                startTime = 0;
            }
        }
    }

    public static void startBench() {
        bench = true;
        if (WorldRenderer.getInstance() != null) {
            WorldRenderer.getInstance().allChanged();
        }
    }

    public static float getDeltaTime() {
        return deltaTime;
    }
}
