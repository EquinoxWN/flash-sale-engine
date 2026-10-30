# RFC 0001: flash-sale-engine design

- **Status:** Accepted for M1 (stock admission); M2 and M3 sections are plans
- **Author:** EquinoxWN
- **Created:** 2026-10-23

## Problem (Define)

A flash sale puts a small, fixed stock (say 1,000 units) in front of a crowd that is hundreds of
times larger, all arriving in the same few seconds. Two things go wrong in a naive design:

1. **Overselling.** "Read stock, check it is above zero, write stock minus one" done by many
   application threads at once lets several buyers read the same last unit. The shop then owes
   units it does not have.
2. **Collapse under load.** If every request reaches the order database, the database is the
   first thing to fall over, and it takes the rest of the shop with it.

Users of this project are backend engineers who need a reference for the admission and stock part
of such a system: how to shed the crowd early and how to make "check and take one" a single step.

### Critical-to-quality measures

| CTQ | Target | How it is measured in M1 |
|---|---|---|
| Units sold beyond stock | **0**, in every run | `audit` script: `remaining >= 0`, `units in orders <= units`, counter and orders agree |
| Units sold when demand exceeds stock | exactly equal to stock | burst and oversell tests assert `won == units` and `remaining == 0` |
| Requests reaching Valkey while overloaded | bounded by the rate limit and the in-flight cap | gateway counters `shedByRateLimit`, `shedByBusy`; burst summary |
| Per-user limit | never exceeded | audit: largest order `<= perUserLimit` |
| Winners without a queue token | 0 | audit: every order's user is in the tokens hash |

## Goals

- M1: before the sale, stock is loaded into Valkey, and the gateway sheds excess traffic with rate
  limits and queue tokens.
- M1: a Lua script checks and decrements stock atomically in Valkey, so thousands of concurrent
  buyers can never oversell.
- M2: winners get a message on a queue, and order workers write to the database at a pace it can
  sustain; order creation is idempotent per user and item.
- M3: unpaid orders expire and return stock, a reconciliation job compares Valkey, queue and
  database, and a k6 burst of 50k virtual users is checked for zero oversold units.

## Non-goals

- Running as a hosted production service.
- Authentication: M1 trusts an `X-User-Id` header that an authenticating proxy must set.
- Payments, orders in a database and the message queue (M2).
- TLS to Valkey and Valkey Cluster deployment (keys are cluster-ready, but only one node is tested).

## Design (M1)

```
buyer --HTTP--> Gateway --(rate limit)--(in-flight cap)--> Valkey Lua scripts
                  |  429 rate_limited     503 busy            load / admit / buy / status / audit
```

### Valkey data model

Every key of a sale carries the hash tag `{saleId}`, so all of them live in one cluster slot,
which a multi-key Lua script requires.

| Key | Type | Holds |
|---|---|---|
| `fs:{id}:stock` | string (integer) | units left |
| `fs:{id}:meta` | hash | `units`, `tokenCap`, `perUserLimit` |
| `fs:{id}:tokens` | hash | user id to queue token |
| `fs:{id}:orders` | hash | user id to units bought |

All four keys expire with the sale's TTL (set on load; the tokens and orders hashes copy the stock
key's remaining TTL when they are created).

### Scripts

- `load.lua` writes the stock and settings before the sale. It refuses to overwrite a sale that
  exists unless `--force` is given, so a mistaken second load cannot reset stock mid-sale.
- `admit.lua` issues at most one queue token per user and at most `tokenCap` tokens in total
  (default: twice the units). Asking again returns the same token. Once stock is zero, new users
  get "sold out" without a token.
- `buy.lua` is the only place stock is taken. In one script it checks the user's token, the
  per-user limit and the stock, then `DECRBY`s the stock and records the order. Valkey runs a
  script to completion before any other command, so no other buyer can run between the check and
  the decrement.
- `status.lua` and `audit.lua` read a consistent snapshot; `audit` walks every order and is the
  oversell checker used by tests, the CLI and CI.

The Java side (`LuaScript`) sends `EVALSHA` and falls back to `EVAL` only on `NOSCRIPT`, so the
script body crosses the network once per server, not once per purchase.

### Gateway

`com.sun.net.httpserver` on virtual threads, bound to `127.0.0.1` unless `--bind` says otherwise.
Shedding happens in this order, each step cheaper than the next:

1. Malformed requests are refused without any Valkey call (ids are validated against strict
   patterns, bodies are refused, quantity is 1 to 100).
2. A GCRA token bucket (`RateLimiter`, one CAS per request, no lock) answers `429 rate_limited`.
3. A semaphore caps requests talking to Valkey; a request that cannot get a slot in time answers
   `503 busy`. Both carry `Retry-After: 1`.
4. `admit.lua` answers `429 queue_full` once the token cap is reached, so at most `tokenCap`
   buyers ever reach `buy.lua`.

## Alternatives considered

| Option | Why not |
|---|---|
| `WATCH` / `MULTI` / `EXEC` optimistic transaction | Correct, but under contention most transactions abort and retry; with thousands of buyers on one key the retry storm multiplies Valkey load exactly when it is highest. A script never aborts. |
| `DECR` first, then `INCR` back when the result is negative | One round trip and atomic per command, but the counter visibly goes below zero, the per-user limit and token check need more commands that are not atomic with it, and a crash between the two leaks stock. |
| Row lock in the database (`SELECT ... FOR UPDATE`) | Correct, but every buyer becomes a database transaction on one hot row: the database load this design exists to avoid. |
| Distributed lock (Redlock) around read-check-write | Adds lock acquisition round trips and lease-expiry edge cases for something one script already does atomically. |
| Spring Boot for the gateway | Larger dependency tree to audit and slower start for three routes; see [ADR 0003](../adr/0003-jdk-http-server-and-jedis-mock.md). |

## Measurement plan (Measure, Analyze, Improve, Control)

- **Measure:** `OversellTest` (2,000 concurrent buyers for 100 units; 25 seeded random sales) and
  `BurstTest` (5,000 HTTP buyers for 500 units) assert the CTQs above; `make demo` prints
  requests per second, latency percentiles, shed counts and the audit for 20,000 buyers.
- **Analyze:** a negative control (`nonAtomicCheckThenDecrementOversellsAndTheAuditCatchesIt`)
  forces the read-then-write race in Java and must oversell, proving the checker can see a defect;
  a mutation of `buy.lua` must make tests fail.
- **Improve:** the M1 change is the atomic script itself plus shedding in front of it.
- **Control:** CI runs every test against jedis-mock and against a real Valkey 9.0 container,
  runs a 20,000-buyer burst against Valkey whose audit must pass (`::notice` with the result), and
  audits dependencies with OSV-Scanner. JUnit has a 120-second default timeout so a concurrency
  bug fails instead of hanging.

Results: [docs/results/m1.md](../results/m1.md).

## Milestones

- **M1 (done):** stock preloaded into Valkey; gateway with rate limit, in-flight cap and queue
  tokens; atomic Lua purchase; oversell audit.
- **M2:** winners get a message on a queue, order workers write to the database at a pace it can
  sustain; idempotent order creation per user and item.
- **M3:** unpaid orders expire and return stock; reconciliation of Valkey, queue and database;
  k6 burst of 50k virtual users with zero oversold units and flat database load.

## Risks and open questions

- **jedis-mock is not Valkey.** Local tests run Lua on LuaJ (Lua 5.2 semantics) inside a Java
  server; Valkey runs Lua 5.1. Scripts avoid features that differ, and CI runs the same tests on
  real Valkey 9.0. Throughput numbers from jedis-mock say nothing about Valkey's throughput.
- **One hot key.** All buyers of a sale hit one Valkey key, so one node's single command thread
  is the ceiling. Sharding stock across several keys (buckets) is a possible M3 experiment.
- **Trusted user header.** Without an authenticating proxy, a client can claim any user id and
  collect one token per invented id; the token cap still bounds how many reach `buy.lua`.
- **The audit walks every order** in one script; `MAX_UNITS` (10,000,000) keeps that bounded, but
  it should run after the sale, not during it.
