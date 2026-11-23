package net.vulkanmod.render.chunk.build.task;

import com.google.common.collect.Queues;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskDispatcher {
    private final Queue<CompileResult> highPriorityResults = new ConcurrentLinkedQueue<>();
    private final Queue<CompileResult> compileResults = new ConcurrentLinkedQueue<>();
    public final ThreadBuilderPack fixedBuffers;

    private volatile boolean stopThreads;
    private Thread[] threads;
    private BuilderResources[] resources;
    private final AtomicInteger idleThreads = new AtomicInteger(0);
    private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();

    public TaskDispatcher() {
        this.fixedBuffers = new ThreadBuilderPack();

        this.stopThreads = true;
    }

    public void createThreads() {
        int n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
        createThreads(n);
    }

    public void createThreads(int n) {
        if (!this.stopThreads) {
            this.stopThreads();
        }

        this.stopThreads = false;

        if (this.resources != null) {
            for (BuilderResources resources : this.resources) {
                resources.free();
            }
        }

        // Auto select thread count
        if (n == 0) {
            n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
        }

        this.threads = new Thread[n];
        this.resources = new BuilderResources[n];

        for (int i = 0; i < n; i++) {
            BuilderResources builderResources = new BuilderResources();
            Thread thread = new Thread(() -> runTaskThread(builderResources),
                                       "Builder-" + i);
            thread.setPriority(Thread.NORM_PRIORITY);

            this.threads[i] = thread;
            this.resources[i] = builderResources;
            thread.start();
        }
    }

    private void runTaskThread(BuilderResources builderResources) {
        while (!this.stopThreads) {
            ChunkTask task = this.pollTask();

            if (task == null) {
                synchronized (this) {
                    // Re-check inside lock to avoid lost wakeups
                    task = this.pollTask();
                    if (task == null && !this.stopThreads) {
                        this.idleThreads.incrementAndGet();
                        try {
                            this.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        } finally {
                            this.idleThreads.decrementAndGet();
                        }
                    }
                }
            }

            if (task == null)
                continue;

            task.runTask(builderResources);
        }
    }

    public void schedule(ChunkTask chunkTask) {
        if (chunkTask == null)
            return;

        if (chunkTask.highPriority) {
            this.highPriorityTasks.offer(chunkTask);
        }
        else {
            this.lowPriorityTasks.offer(chunkTask);
        }

        if (this.idleThreads.get() > 0) {
            synchronized (this) {
                this.notify();
            }
        }
    }

    @Nullable
    private ChunkTask pollTask() {
        ChunkTask task = this.highPriorityTasks.poll();

        if (task == null)
            task = this.lowPriorityTasks.poll();

        return task;
    }

    public void stopThreads() {
        if (this.stopThreads)
            return;

        this.stopThreads = true;

        synchronized (this) {
            this.notifyAll();
        }

        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public boolean updateSections() {
        CompileResult result;
        boolean flag = false;

        // Always drain player-triggered high-priority updates immediately
        while ((result = this.highPriorityResults.poll()) != null) {
            flag = true;
            doSectionUpdate(result);
        }

        int budgetMs = Initializer.CONFIG.chunkUploadBudgetMs;
        if (budgetMs <= 0) {
            while ((result = this.compileResults.poll()) != null) {
                flag = true;
                doSectionUpdate(result);
            }
        } else {
            long deadlineNs = System.nanoTime() + (budgetMs * 1_000_000L);
            int uploaded = 0;
            while ((result = this.compileResults.poll()) != null) {
                flag = true;
                doSectionUpdate(result);
                uploaded++;
                if (uploaded >= 1 && System.nanoTime() >= deadlineNs) {
                    break;
                }
            }
        }

        return flag;
    }

    public void scheduleSectionUpdate(CompileResult compileResult) {
        if (compileResult.highPriority) {
            this.highPriorityResults.offer(compileResult);
        } else {
            this.compileResults.offer(compileResult);
        }
    }

    private void doSectionUpdate(CompileResult compileResult) {
        RenderSection section = compileResult.renderSection;
        ChunkArea renderArea = section.getChunkArea();
        DrawBuffers drawBuffers = renderArea.getDrawBuffers();

        // Check if area has been dismissed before uploading
        ChunkAreaManager chunkAreaManager = WorldRenderer.getInstance().getChunkAreaManager();
        if (chunkAreaManager.getChunkArea(renderArea.index) != renderArea) {
            compileResult.renderedLayers.values()
                                        .forEach(UploadBuffer::release);
            return;
        }

        if (compileResult.fullUpdate) {
            var renderLayers = compileResult.renderedLayers;
            for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
                UploadBuffer uploadBuffer = renderLayers.get(renderType);

                if (uploadBuffer != null) {
                    drawBuffers.upload(section, uploadBuffer, renderType);
                }
                else {
                    section.resetDrawParameters(renderType);
                }
            }

            compileResult.updateSection();
        }
        else {
            UploadBuffer uploadBuffer = compileResult.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            drawBuffers.upload(section, uploadBuffer, TerrainRenderType.TRANSLUCENT);
        }
    }

    public boolean isIdle() {
        return this.idleThreads.get() == this.threads.length
                && this.compileResults.isEmpty()
                && this.highPriorityResults.isEmpty();
    }

    public void clearBatchQueue() {
        while (!this.highPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.highPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.cancel();
            }
        }

        while (!this.lowPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.lowPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.cancel();
            }
        }
    }

    public String getStats() {
        int taskCount = highPriorityTasks.size() + lowPriorityTasks.size();
        return String.format("iT: %d Ts: %d", this.idleThreads.get(), taskCount);
    }

    public BuilderResources[] getResourcesArray() {
        return resources;
    }
}
