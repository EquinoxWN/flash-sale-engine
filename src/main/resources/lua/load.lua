-- Load a sale's stock before it opens. Refuses to overwrite a loaded sale unless forced.
-- KEYS: 1 stock, 2 meta, 3 tokens, 4 orders
-- ARGV: 1 units, 2 token cap, 3 per-user limit, 4 ttl seconds, 5 force ("1" or "0")
-- Returns 1 when loaded, 0 when the sale already exists and force is off.
if ARGV[5] ~= '1' and redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[4])
redis.call('HSET', KEYS[2], 'units', ARGV[1], 'tokenCap', ARGV[2], 'perUserLimit', ARGV[3])
redis.call('EXPIRE', KEYS[2], ARGV[4])
return 1
