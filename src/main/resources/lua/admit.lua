-- Issue one queue token per user, up to the sale's token cap.
-- KEYS: 1 stock, 2 meta, 3 tokens
-- ARGV: 1 user id, 2 candidate token
-- Returns {code, token}: 1 issued, 2 already issued (same token), 0 sold out,
-- -2 sale not loaded, -3 queue full.
local stock = redis.call('GET', KEYS[1])
if not stock then
  return {-2, ''}
end
local existing = redis.call('HGET', KEYS[3], ARGV[1])
if existing then
  return {2, existing}
end
if tonumber(stock) <= 0 then
  return {0, ''}
end
local cap = tonumber(redis.call('HGET', KEYS[2], 'tokenCap'))
if redis.call('HLEN', KEYS[3]) >= cap then
  return {-3, ''}
end
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
local ttl = redis.call('PTTL', KEYS[1])
if ttl > 0 and redis.call('PTTL', KEYS[3]) == -1 then
  redis.call('PEXPIRE', KEYS[3], ttl)
end
return {1, ARGV[2]}
