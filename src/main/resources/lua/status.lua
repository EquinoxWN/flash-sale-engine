-- Cheap read of a sale's counters for the status endpoint.
-- KEYS: 1 stock, 2 meta, 3 tokens
-- Returns {loaded, remaining, units, tokens issued, token cap, per-user limit}.
local stock = redis.call('GET', KEYS[1])
if not stock then
  return {0, 0, 0, 0, 0, 0}
end
local meta = redis.call('HMGET', KEYS[2], 'units', 'tokenCap', 'perUserLimit')
return {1, tonumber(stock), tonumber(meta[1]), redis.call('HLEN', KEYS[3]),
        tonumber(meta[2]), tonumber(meta[3])}
