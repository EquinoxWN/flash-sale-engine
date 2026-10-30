-- Check and decrement stock in one atomic step: the only place stock is ever taken.
-- KEYS: 1 stock, 2 meta, 3 tokens, 4 orders
-- ARGV: 1 user id, 2 queue token, 3 quantity
-- Returns {code, remaining}: 1 won, 0 sold out, -1 per-user limit reached,
-- -2 sale not loaded, -4 token missing or wrong, -5 fewer units left than requested.
local stock = redis.call('GET', KEYS[1])
if not stock then
  return {-2, 0}
end
stock = tonumber(stock)
if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then
  return {-4, stock}
end
local qty = tonumber(ARGV[3])
local limit = tonumber(redis.call('HGET', KEYS[2], 'perUserLimit'))
local bought = tonumber(redis.call('HGET', KEYS[4], ARGV[1]) or '0')
if bought + qty > limit then
  return {-1, stock}
end
if stock <= 0 then
  return {0, 0}
end
if stock < qty then
  return {-5, stock}
end
local left = redis.call('DECRBY', KEYS[1], qty)
redis.call('HINCRBY', KEYS[4], ARGV[1], qty)
local ttl = redis.call('PTTL', KEYS[1])
if ttl > 0 and redis.call('PTTL', KEYS[4]) == -1 then
  redis.call('PEXPIRE', KEYS[4], ttl)
end
return {1, left}
