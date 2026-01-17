package com.ratelimiter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * High-performance distributed rate limiter using the Sliding Window Log algorithm.
 * This implementation uses Redis Sorted Sets (ZSET) with Lua scripts for atomic operations.
 * 
 * Thread-safe and designed for high-throughput scenarios using the Lettuce Redis client.
 */
public class SlidingWindowRateLimiter implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final String luaScriptSha;
    private final long windowSizeMs;
    private final int maxRequests;
    
    /**
     * Creates a new SlidingWindowRateLimiter instance.
     * 
     * @param redisHost The Redis server hostname
     * @param redisPort The Redis server port
     * @param windowSizeMs The sliding window size in milliseconds
     * @param maxRequests The maximum number of requests allowed within the window
     */
    public SlidingWindowRateLimiter(String redisHost, int redisPort, long windowSizeMs, int maxRequests) {
        this.windowSizeMs = windowSizeMs;
        this.maxRequests = maxRequests;
        
        // Create Redis client with connection URI
        RedisURI redisUri = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build();
        
        this.redisClient = RedisClient.create(redisUri);
        this.connection = redisClient.connect();
        
        // Load and cache the Lua script
        this.luaScriptSha = loadAndCacheLuaScript();
        
        logger.info("SlidingWindowRateLimiter initialized with window={}ms, maxRequests={}", 
                    windowSizeMs, maxRequests);
    }
    
    /**
     * Loads the Lua script from resources and caches it in Redis using SCRIPT LOAD.
     * This enables using EVALSHA instead of EVAL, reducing network overhead.
     * 
     * @return The SHA-1 digest of the cached script
     */
    private String loadAndCacheLuaScript() {
        try {
            String luaScript = loadLuaScriptFromResources();
            RedisCommands<String, String> syncCommands = connection.sync();
            String sha = syncCommands.scriptLoad(luaScript);
            logger.debug("Lua script loaded and cached with SHA: {}", sha);
            return sha;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Lua script", e);
        }
    }
    
    /**
     * Reads the Lua script from the classpath resources.
     * 
     * @return The Lua script as a String
     * @throws IOException if the script cannot be read
     */
    private String loadLuaScriptFromResources() throws IOException {
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("rate_limiter.lua")) {
            
            if (inputStream == null) {
                throw new IOException("rate_limiter.lua not found in classpath");
            }
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
    
    /**
     * Checks if a request from the given client ID is allowed under the rate limit.
     * This is a synchronous blocking operation.
     * 
     * @param clientId The unique identifier for the client (e.g., user ID, IP address)
     * @return true if the request is allowed, false if rate limited
     */
    public boolean isAllowed(String clientId) {
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + clientId;
        long currentTimestamp = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        
        RedisCommands<String, String> syncCommands = connection.sync();
        
        try {
            // Execute the Lua script using EVALSHA (cached script)
            Long result = syncCommands.evalsha(
                    luaScriptSha,
                    ScriptOutputType.INTEGER,
                    new String[]{rateLimitKey},
                    String.valueOf(currentTimestamp),
                    String.valueOf(windowSizeMs),
                    String.valueOf(maxRequests),
                    requestId
            );
            
            boolean allowed = result != null && result == 1L;
            
            if (logger.isDebugEnabled()) {
                logger.debug("Rate limit check for client {}: {}", clientId, 
                            allowed ? "ALLOWED" : "DENIED");
            }
            
            return allowed;
            
        } catch (Exception e) {
            logger.error("Error checking rate limit for client {}", clientId, e);
            // Fail open: allow the request if there's an error
            return true;
        }
    }
    
    /**
     * Asynchronous version of isAllowed() that returns a CompletableFuture.
     * This enables non-blocking rate limit checks for high-throughput scenarios.
     * 
     * @param clientId The unique identifier for the client
     * @return CompletableFuture that resolves to true if allowed, false if rate limited
     */
    public CompletableFuture<Boolean> isAllowedAsync(String clientId) {
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + clientId;
        long currentTimestamp = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        
        RedisAsyncCommands<String, String> asyncCommands = connection.async();
        
        // Execute the Lua script asynchronously using EVALSHA
        return asyncCommands.evalsha(
                luaScriptSha,
                ScriptOutputType.INTEGER,
                new String[]{rateLimitKey},
                String.valueOf(currentTimestamp),
                String.valueOf(windowSizeMs),
                String.valueOf(maxRequests),
                requestId
        )
        .toCompletableFuture()
        .thenApply(result -> {
            boolean allowed = result != null && ((Long) result) == 1L;
            
            if (logger.isDebugEnabled()) {
                logger.debug("Async rate limit check for client {}: {}", clientId,
                            allowed ? "ALLOWED" : "DENIED");
            }
            
            return allowed;
        })
        .exceptionally(throwable -> {
            logger.error("Error in async rate limit check for client {}", clientId, throwable);
            // Fail open: allow the request if there's an error
            return true;
        });
    }
    
    /**
     * Gets the configured window size in milliseconds.
     * 
     * @return The window size in milliseconds
     */
    public long getWindowSizeMs() {
        return windowSizeMs;
    }
    
    /**
     * Gets the maximum number of requests allowed within the window.
     * 
     * @return The maximum number of requests
     */
    public int getMaxRequests() {
        return maxRequests;
    }
    
    /**
     * Closes the Redis connection and cleans up resources.
     * Should be called when the rate limiter is no longer needed.
     */
    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        logger.info("SlidingWindowRateLimiter closed");
    }
}
