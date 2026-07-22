-- KEYS[1] = walletKey
-- ARGV[1] = totalDbBalance

local totalDbBalance = tonumber(ARGV[1])
local locked = tonumber(redis.call('HGET', KEYS[1], 'locked') or '0')

if locked > totalDbBalance then
    locked = totalDbBalance
end

local balance = totalDbBalance - locked
redis.call('HSET', KEYS[1], 'balance', tostring(balance), 'locked', tostring(locked))

return {tostring(balance), tostring(locked)}
