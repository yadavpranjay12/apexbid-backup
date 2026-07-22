-- KEYS[1] = auction_key ("auction:123")
-- KEYS[2] = active_zset ("auctions:active")
-- KEYS[3] = highest_bids_zset ("auctions:highest_bids")
-- KEYS[4] = most_active_zset ("auctions:most_active")
-- KEYS[5] = settlement_queue ("queue:settlement")
-- ARGV[1] = auction_id
-- ARGV[2] = channelAuctionUpdates ("auction:updates")

local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'ACTIVE' then
    return 0
end

redis.call('HSET', KEYS[1], 'status', 'ENDED')
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])

local payload = cjson.encode({
    type = "SETTLEMENT",
    auctionId = ARGV[1]
})
redis.call('LPUSH', KEYS[5], payload)

-- Broadcast the status change to the frontend using dynamic channel
local payload = cjson.encode({
    auctionId = ARGV[1],
    status = "ENDED"
})
redis.call('PUBLISH', ARGV[2], payload)

return 1