-- KEYS[1] = walletKey
-- ARGV[1] = balance
-- ARGV[2] = locked
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('HSET', KEYS[1], 'balance', ARGV[1], 'locked', ARGV[2])
    return 1
end
return 0
