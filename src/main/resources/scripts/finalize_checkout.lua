-- KEYS[1] = winnerWalletKey
-- KEYS[2] = sellerWalletKey
-- KEYS[3] = checkoutQueueKey
-- ARGV[1] = remainingAmount
-- ARGV[2] = winnerId
-- ARGV[3] = sellerId
-- ARGV[4] = auctionId
-- ARGV[5] = channelWalletUpdates ("wallet:updates")

local winnerWallet = KEYS[1]
local sellerWallet = KEYS[2]
local checkoutQueue = KEYS[3]
local remainingAmount = tonumber(ARGV[1])
local winnerId = tonumber(ARGV[2])
local sellerId = tonumber(ARGV[3])
local auctionId = ARGV[4]
local channelWalletUpdates = ARGV[5]

-- 1. Cache-Aside Checks
if redis.call('EXISTS', winnerWallet) == 0 then return -1 end
if redis.call('EXISTS', sellerWallet) == 0 then return -2 end

local winnerBalance = tonumber(redis.call('HGET', winnerWallet, 'balance') or "0")
if winnerBalance < remainingAmount then return -3 end

-- 2. Lock the money from the buyer atomically (seller gets paid by the worker)
redis.call('HINCRBY', winnerWallet, 'balance', -remainingAmount)
redis.call('HINCRBY', winnerWallet, 'locked', remainingAmount)

-- 3. Push to background worker queue
local payload = cjson.encode({
    type = "CHECKOUT",
    auctionId = auctionId
})
redis.call('LPUSH', checkoutQueue, payload)

-- 4. Broadcast updates to WebSockets using dynamic channel
local wAvail = redis.call('HGET', winnerWallet, 'balance') or "0"
local wLocked = redis.call('HGET', winnerWallet, 'locked') or "0"
redis.call('PUBLISH', channelWalletUpdates, cjson.encode({userId = winnerId, balance = wAvail, locked = wLocked}))

local sAvail = redis.call('HGET', sellerWallet, 'balance') or "0"
local sLocked = redis.call('HGET', sellerWallet, 'locked') or "0"
redis.call('PUBLISH', channelWalletUpdates, cjson.encode({userId = sellerId, balance = sAvail, locked = sLocked}))

return 1