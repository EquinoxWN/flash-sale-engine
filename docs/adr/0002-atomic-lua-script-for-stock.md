# ADR 0002: Take stock only inside one Valkey Lua script

- **Status:** Accepted (M1)

## Context

Thousands of buyers try to take the last units at the same moment. The check ("is there stock,
does this user hold a token, is the user under the per-user limit") and the write ("decrement
stock, record the order") must happen as one step, or two buyers can both pass the check for the
same unit. Valkey offers three ways to do that: an optimistic `WATCH`/`MULTI`/`EXEC` transaction,
single atomic commands (`DECR` and compensate), or a Lua script, which Valkey runs to completion
before serving any other command.

## Decision

All stock changes go through `buy.lua`, which performs the token check, the per-user limit, the
stock check, `DECRBY` and `HINCRBY` of the order in one script. Loading, admission and the audit
are scripts too. Java calls them with `EVALSHA` and resends the source only on `NOSCRIPT`.

## Consequences

- **Good:** no aborted transactions and no retries under contention; one round trip per purchase;
  the counter never goes below zero; the invariants live next to the data in 30 lines of Lua.
- **Cost:** a script blocks the Valkey node while it runs, so scripts must stay short and must not
  loop over unbounded data on the hot path (only the after-sale `audit.lua` walks all orders, and
  units are capped at 10,000,000).
- **Cost:** every key a script touches must be in one cluster slot; the `{saleId}` hash tag
  guarantees that, but it also means one sale cannot be spread across cluster nodes.
- **Cost:** two runtimes to keep compatible: scripts are written for Lua 5.1 (Valkey) and also run
  on LuaJ in jedis-mock for local tests, so they use only basic Lua.
- The negative control test shows the non-atomic version oversells by 7 units out of 1 with 8
  buyers, and a mutation that removes the stock check from the script fails 6 of 70 tests.
