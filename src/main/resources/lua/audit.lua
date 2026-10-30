-- Consistent snapshot for the oversell check: walks the orders hash in one atomic step.
-- KEYS: 1 stock, 2 meta, 3 tokens, 4 orders
-- Returns {loaded, remaining, units, per-user limit, tokens issued, units in orders,
--          winners, largest order, winners without a token}.
local stock = redis.call('GET', KEYS[1])
if not stock then
  return {0, 0, 0, 0, 0, 0, 0, 0, 0}
end
local meta = redis.call('HMGET', KEYS[2], 'units', 'perUserLimit')
local orders = redis.call('HGETALL', KEYS[4])
local sold, winners, largest, tokenless = 0, 0, 0, 0
for i = 1, #orders, 2 do
  local qty = tonumber(orders[i + 1])
  sold = sold + qty
  winners = winners + 1
  if qty > largest then
    largest = qty
  end
  if redis.call('HEXISTS', KEYS[3], orders[i]) == 0 then
    tokenless = tokenless + 1
  end
end
return {1, tonumber(stock), tonumber(meta[1]), tonumber(meta[2]), redis.call('HLEN', KEYS[3]),
        sold, winners, largest, tokenless}
