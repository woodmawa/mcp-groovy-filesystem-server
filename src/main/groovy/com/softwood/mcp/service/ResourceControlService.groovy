package com.softwood.mcp.service

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.*

/**
 * Modern resource control service for Java 25+
 * Uses structured concurrency and resource limits instead of deprecated Security Manager
 */
@Service
@Slf4j
class ResourceControlService {
    
    @Value('${mcp.script.max-memory-mb:256}')
    int maxMemoryMb
    
    @Value('${mcp.script.max-threads:10}')
    int maxThreads
    
    @Value('${mcp.script.max-execution-time-seconds:60}')
    int maxExecutionTimeSeconds
    
    private final ThreadPoolExecutor scriptExecutor
    private final Map<String, ResourceMonitor> activeMonitors = new ConcurrentHashMap<>()
    
    ResourceControlService() {
        // Create bounded thread pool for script execution
        this.scriptExecutor = new ThreadPoolExecutor(
            2,  // core pool size
            10,  // max pool size (will be updated after @Value injection)
            60L,  // keep alive time
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(100),  // bounded queue
            new ThreadPoolExecutor.CallerRunsPolicy()  // rejection policy
        )
    }
    
    /**
     * Execute a task with resource limits using Virtual Threads (Java 21+)
     */
    <T> T executeWithLimits(
        String taskId,
        Callable<T> task,
        int timeoutSeconds = maxExecutionTimeSeconds
    ) {
        ResourceMonitor monitor = new ResourceMonitor(taskId)
        activeMonitors.put(taskId, monitor)
        
        try {
            // Use Virtual Thread executor for lightweight concurrency
            ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
            
            try {
                monitor.start()
                Future<T> future = virtualExecutor.submit({
                    scriptExecutor.submit(task).get(timeoutSeconds, TimeUnit.SECONDS)
                } as Callable<T>)
                
                return future.get()
            } finally {
                monitor.stop()
                virtualExecutor.shutdown()
            }
            
        } catch (TimeoutException e) {
            monitor.markTimeout()
            log.error("Task ${taskId} timed out after ${timeoutSeconds}s")
            throw new RuntimeException("Execution timed out after ${timeoutSeconds} seconds", e)
            
        } catch (ExecutionException e) {
            log.error("Task ${taskId} failed", e.cause)
            throw new RuntimeException("Execution failed: ${e.cause.message}", e.cause)
            
        } finally {
            activeMonitors.remove(taskId)
        }
    }
    
    /**
     * Check if resource limits are being approached
     */
    boolean isNearResourceLimit() {
        long usedMemory = Runtime.runtime.totalMemory() - Runtime.runtime.freeMemory()
        long usedMemoryMb = (long)(usedMemory / (1024 * 1024))
        
        return usedMemoryMb > (maxMemoryMb * 0.8) || 
               scriptExecutor.activeCount >= (maxThreads * 0.8)
    }
    
    /**
     * Get current resource usage
     */
    Map<String, Object> getResourceUsage() {
        long totalMemory = Runtime.runtime.totalMemory()
        long freeMemory = Runtime.runtime.freeMemory()
        long usedMemory = totalMemory - freeMemory
        long maxMemory = Runtime.runtime.maxMemory()
        
        return [
            usedMemoryMb: (long)(usedMemory / (1024 * 1024)),
            totalMemoryMb: (long)(totalMemory / (1024 * 1024)),
            maxMemoryMb: (long)(maxMemory / (1024 * 1024)),
            activeThreads: scriptExecutor.activeCount,
            maxThreads: maxThreads,
            queuedTasks: scriptExecutor.queue.size(),
            activeTasks: activeMonitors.size(),
            nearLimit: isNearResourceLimit()
        ]
    }
    
    /**
     * Force garbage collection if approaching limits
     */
    void triggerGCIfNeeded() {
        if (isNearResourceLimit()) {
            log.warn("Approaching resource limits, triggering GC")
            System.gc()
        }
    }
    
    /**
     * Shutdown executor
     */
    void shutdown() {
        log.info("Shutting down resource control service")
        scriptExecutor.shutdown()
        try {
            if (!scriptExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                scriptExecutor.shutdownNow()
            }
        } catch (InterruptedException e) {
            scriptExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
    
    /**
     * Resource monitor for tracking individual task execution
     */
    static class ResourceMonitor {
        final String taskId
        long startTime
        long endTime
        boolean timedOut = false
        
        ResourceMonitor(String taskId) {
            this.taskId = taskId
        }
        
        void start() {
            startTime = System.currentTimeMillis()
        }
        
        void stop() {
            endTime = System.currentTimeMillis()
        }
        
        void markTimeout() {
            timedOut = true
            stop()
        }
        
        long getDurationMs() {
            endTime > 0 ? endTime - startTime : System.currentTimeMillis() - startTime
        }
    }
}
