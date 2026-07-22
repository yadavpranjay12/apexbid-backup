-- KEYS[1] = wallet hash key     (wallet:<userId>)
-- KEYS[2] = withdrawal queue    (wallet:withdrawal:queue)
-- ARGV[1] = amount to withdraw
-- ARGV[2] = userId
-- ARGV[3] = requestId (UUID for idempotency)
-- ARGV[4] = channelWalletUpdates

-- 1. Check wallet exists in Redis
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1  -- Needs hydration
end

-- 2. Check available balance
local balance = tonumber(redis.call('HGET', KEYS[1], 'balance') or '0')
local amount = tonumber(ARGV[1])

if balance < amount then
    return -2  -- Insufficient available funds
end

-- 3. Atomic: Move money to locked atomically
redis.call('HINCRBY', KEYS[1], 'balance', -amount)
redis.call('HINCRBY', KEYS[1], 'locked', amount)

local payload = cjson.encode({
    type = "WITHDRAWAL",
    userId = ARGV[2],
    amount = ARGV[1],
    requestId = ARGV[3]
})
redis.call('LPUSH', KEYS[2], payload)

-- 4. Publish wallet update for real-time UI
local newBalance = redis.call('HGET', KEYS[1], 'balance')
local locked = redis.call('HGET', KEYS[1], 'locked') or '0'
redis.call('PUBLISH', ARGV[4], cjson.encode({
    userId = ARGV[2],
    balance = newBalance,
    locked = locked
}))

return 1
