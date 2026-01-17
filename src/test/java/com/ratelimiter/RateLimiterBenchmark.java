package com.ratelimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Comprehensive benchmark suite for the SlidingWindowRateLimiter.
 * 
 * This benchmark:
 * - Simulates 10,000+ concurrent requests
 * - Measures latency statistics (min, max, avg, p50, p95, p99)
 * - Includes a warmup phase to stabilize JIT compilation
 * - Proves sub-millisecond overhead claim
 */
public class RateLimiterBenchmark {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterBenchmark.class);
    
    // Benchmark configuration
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final long WINDOW_SIZE_MS = 1000; // 1 second window
    private static final int MAX_REQUESTS = 100; // 100 requests per second
    private static final int WARMUP_REQUESTS = 1000;
    private static final int BENCHMARK_REQUESTS = 10000;
    private static final int CONCURRENT_THREADS = 100;
    
    /**
     * Main entry point for the benchmark.
     */
    public static void main(String[] args) throws Exception {
        logger.info("=== Sliding Window Rate Limiter Benchmark ===");
        logger.info("Configuration:");
        logger.info("  Redis: {}:{}", REDIS_HOST, REDIS_PORT);
        logger.info("  Window Size: {}ms", WINDOW_SIZE_MS);
        logger.info("  Max Requests: {}", MAX_REQUESTS);
        logger.info("  Benchmark Requests: {}", BENCHMARK_REQUESTS);
        logger.info("  Concurrent Threads: {}", CONCURRENT_THREADS);
        logger.info("");
        
        try (SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter(
                REDIS_HOST, REDIS_PORT, WINDOW_SIZE_MS, MAX_REQUESTS)) {
            
            // Phase 1: Warmup
            logger.info("Phase 1: Warmup ({} requests)...", WARMUP_REQUESTS);
            runWarmup(rateLimiter);
            logger.info("Warmup completed. Waiting 2 seconds before benchmark...");
            Thread.sleep(2000);
            
            // Phase 2: Synchronous Benchmark
            logger.info("Phase 2: Synchronous Benchmark ({} requests)...", BENCHMARK_REQUESTS);
            BenchmarkResult syncResult = runSynchronousBenchmark(rateLimiter);
            printResults("Synchronous", syncResult);
            
            // Wait between benchmarks
            Thread.sleep(2000);
            
            // Phase 3: Asynchronous Benchmark
            logger.info("Phase 3: Asynchronous Benchmark ({} requests)...", BENCHMARK_REQUESTS);
            BenchmarkResult asyncResult = runAsynchronousBenchmark(rateLimiter);
            printResults("Asynchronous", asyncResult);
            
            // Phase 4: Concurrent Stress Test
            Thread.sleep(2000);
            logger.info("Phase 4: Concurrent Stress Test ({} concurrent requests)...", BENCHMARK_REQUESTS);
            BenchmarkResult concurrentResult = runConcurrentBenchmark(rateLimiter);
            printResults("Concurrent", concurrentResult);
            
        } catch (Exception e) {
            logger.error("Benchmark failed", e);
            throw e;
        }
        
        logger.info("\n=== Benchmark Complete ===");
    }
    
    /**
     * Runs warmup requests to stabilize JIT compilation and establish Redis connection.
     */
    private static void runWarmup(SlidingWindowRateLimiter rateLimiter) {
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            rateLimiter.isAllowed("warmup-client-" + (i % 10));
            
            // Add small delay every 100 requests to avoid hitting rate limit
            if (i % 100 == 0 && i > 0) {
                try {
                    Thread.sleep(WINDOW_SIZE_MS + 10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    /**
     * Runs synchronous benchmark using the blocking isAllowed() method.
     */
    private static BenchmarkResult runSynchronousBenchmark(SlidingWindowRateLimiter rateLimiter) {
        List<Long> latencies = new ArrayList<>(BENCHMARK_REQUESTS);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_REQUESTS; i++) {
            String clientId = "sync-client-" + (i % 100);
            
            long requestStart = System.nanoTime();
            boolean allowed = rateLimiter.isAllowed(clientId);
            long requestEnd = System.nanoTime();
            
            latencies.add((requestEnd - requestStart) / 1000); // Convert to microseconds
            
            if (allowed) {
                allowedCount.incrementAndGet();
            } else {
                deniedCount.incrementAndGet();
            }
            
            // Small delay to spread requests over time
            if (i % 50 == 0 && i > 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        
        return new BenchmarkResult(latencies, allowedCount.get(), deniedCount.get(), totalTimeMs);
    }
    
    /**
     * Runs asynchronous benchmark using CompletableFuture.
     */
    private static BenchmarkResult runAsynchronousBenchmark(SlidingWindowRateLimiter rateLimiter) 
            throws Exception {
        
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(BENCHMARK_REQUESTS));
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        
        long startTime = System.nanoTime();
        
        // Create async requests in batches to avoid overwhelming the system
        int batchSize = 100;
        for (int batch = 0; batch < BENCHMARK_REQUESTS / batchSize; batch++) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(batchSize);
            
            for (int i = 0; i < batchSize; i++) {
                int requestIndex = batch * batchSize + i;
                String clientId = "async-client-" + (requestIndex % 100);
                
                long requestStart = System.nanoTime();
                CompletableFuture<Void> future = rateLimiter.isAllowedAsync(clientId)
                        .thenAccept(allowed -> {
                            long requestEnd = System.nanoTime();
                            latencies.add((requestEnd - requestStart) / 1000); // microseconds
                            
                            if (allowed) {
                                allowedCount.incrementAndGet();
                            } else {
                                deniedCount.incrementAndGet();
                            }
                        });
                
                futures.add(future);
            }
            
            // Wait for batch to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // Small delay between batches
            Thread.sleep(10);
        }
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        
        return new BenchmarkResult(latencies, allowedCount.get(), deniedCount.get(), totalTimeMs);
    }
    
    /**
     * Runs concurrent stress test using thread pool.
     */
    private static BenchmarkResult runConcurrentBenchmark(SlidingWindowRateLimiter rateLimiter) 
            throws Exception {
        
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(BENCHMARK_REQUESTS));
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        
        long startTime = System.nanoTime();
        
        List<CompletableFuture<Void>> futures = IntStream.range(0, BENCHMARK_REQUESTS)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    String clientId = "concurrent-client-" + (i % 100);
                    
                    long requestStart = System.nanoTime();
                    boolean allowed = rateLimiter.isAllowed(clientId);
                    long requestEnd = System.nanoTime();
                    
                    latencies.add((requestEnd - requestStart) / 1000); // microseconds
                    
                    if (allowed) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                }, executor))
                .collect(Collectors.toList());
        
        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        return new BenchmarkResult(latencies, allowedCount.get(), deniedCount.get(), totalTimeMs);
    }
    
    /**
     * Prints formatted benchmark results.
     */
    private static void printResults(String benchmarkName, BenchmarkResult result) {
        logger.info("\n--- {} Benchmark Results ---", benchmarkName);
        logger.info("Total Requests: {}", result.getTotalRequests());
        logger.info("Allowed: {} ({:.2f}%)", result.allowedCount, 
                    100.0 * result.allowedCount / result.getTotalRequests());
        logger.info("Denied: {} ({:.2f}%)", result.deniedCount,
                    100.0 * result.deniedCount / result.getTotalRequests());
        logger.info("Total Time: {}ms", result.totalTimeMs);
        logger.info("Throughput: {:.2f} requests/second", 
                    result.getTotalRequests() * 1000.0 / result.totalTimeMs);
        logger.info("");
        logger.info("Latency Statistics (microseconds):");
        logger.info("  Min:  {:.2f} µs ({:.4f} ms)", result.getMinLatency(), result.getMinLatency() / 1000.0);
        logger.info("  Max:  {:.2f} µs ({:.4f} ms)", result.getMaxLatency(), result.getMaxLatency() / 1000.0);
        logger.info("  Avg:  {:.2f} µs ({:.4f} ms)", result.getAvgLatency(), result.getAvgLatency() / 1000.0);
        logger.info("  P50:  {:.2f} µs ({:.4f} ms)", result.getP50(), result.getP50() / 1000.0);
        logger.info("  P95:  {:.2f} µs ({:.4f} ms)", result.getP95(), result.getP95() / 1000.0);
        logger.info("  P99:  {:.2f} µs ({:.4f} ms)", result.getP99(), result.getP99() / 1000.0);
        
        // Highlight if sub-millisecond overhead is achieved
        if (result.getP95() < 1000) {
            logger.info("\n✓ Sub-millisecond overhead achieved! (P95: {:.2f} µs)", result.getP95());
        } else {
            logger.info("\n✗ P95 latency exceeds 1ms: {:.2f} µs", result.getP95());
        }
    }
    
    /**
     * Container for benchmark results with statistical calculations.
     */
    private static class BenchmarkResult {
        private final List<Long> latencies;
        private final int allowedCount;
        private final int deniedCount;
        private final long totalTimeMs;
        
        public BenchmarkResult(List<Long> latencies, int allowedCount, int deniedCount, long totalTimeMs) {
            this.latencies = new ArrayList<>(latencies);
            Collections.sort(this.latencies);
            this.allowedCount = allowedCount;
            this.deniedCount = deniedCount;
            this.totalTimeMs = totalTimeMs;
        }
        
        public int getTotalRequests() {
            return allowedCount + deniedCount;
        }
        
        public double getMinLatency() {
            return latencies.isEmpty() ? 0 : latencies.get(0);
        }
        
        public double getMaxLatency() {
            return latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        }
        
        public double getAvgLatency() {
            return latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        }
        
        public double getP50() {
            return getPercentile(50);
        }
        
        public double getP95() {
            return getPercentile(95);
        }
        
        public double getP99() {
            return getPercentile(99);
        }
        
        private double getPercentile(int percentile) {
            if (latencies.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(percentile / 100.0 * latencies.size()) - 1;
            index = Math.max(0, Math.min(index, latencies.size() - 1));
            return latencies.get(index);
        }
    }
}
