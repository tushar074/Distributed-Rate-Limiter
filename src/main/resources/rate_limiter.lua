-- Sliding Window Log Rate Limiter
-- This script implements atomic rate limiting using Redis Sorted Sets (ZSET)
--
-- KEYS[1]: The rate limit key (e.g., "rate_limit:user:123")
--
-- ARGV[1]: Current timestamp in milliseconds (score for ZADD)
-- ARGV[2]: Window size in milliseconds
-- ARGV[3]: Maximum number of requests allowed in the window
-- ARGV[4]: Unique request ID (member for ZADD)
--
-- Returns:
--   1 if the request is allowed
--   0 if the request should be rate limited

local rateLimitKey = KEYS[1]
local currentTimestamp = tonumber(ARGV[1])
local windowSizeMs = tonumber(ARGV[2])
local maxRequests = tonumber(ARGV[3])
local requestId = ARGV[4]

-- Calculate the start of the sliding window
local windowStart = currentTimestamp - windowSizeMs

-- Step 1: Remove all entries outside the current sliding window
-- This ensures we only count recent requests within the window
redis.call('ZREMRANGEBYSCORE', rateLimitKey, '-inf', windowStart)

-- Step 2: Count the number of requests in the current window
local currentCount = redis.call('ZCARD', rateLimitKey)

-- Step 3: Check if we're under the rate limit
if currentCount < maxRequests then
    -- We're under the limit, so add this request to the log
    redis.call('ZADD', rateLimitKey, currentTimestamp, requestId)
    
    -- Set expiration on the key to prevent memory leaks
    -- Expire after window size + small buffer
    redis.call('PEXPIRE', rateLimitKey, windowSizeMs + 1000)
    
    return 1  -- Request allowed
else
    -- We've hit the rate limit
    return 0  -- Request denied
end
