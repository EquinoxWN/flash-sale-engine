# ADR 0003: JDK HTTP server for the gateway, jedis-mock locally and real Valkey in CI

- **Status:** Accepted (M1)

## Context

The scaffold named Spring Boot. M1's gateway has four routes and no database, and the owner's
rules require a clean known-vulnerability audit of every resolved dependency, test scope included.
Separately, the development machine runs no Docker, so a real Valkey cannot be started for local
tests, while the core claim (no oversell) must be tested against something that really executes
the Lua scripts over the Redis protocol.

## Decision

1. The gateway uses `com.sun.net.httpserver.HttpServer` from the JDK with a virtual-thread
   executor. Runtime dependencies are Jedis and its transitive libraries only (19 resolved
   dependencies in total, test scope included).
2. Local tests run against jedis-mock, an in-process Redis-protocol server that executes Lua via
   LuaJ. The same test classes are parameterized over backends: when `VALKEY_URL` is set (CI sets
   it to a Valkey 9.0 service container pinned by digest), the Lua, oversell and burst tests run
   against both.

## Consequences

- **Good:** the audit surface is small (OSV: 19 dependencies, 0 known vulnerabilities); start-up
  is instant; the whole suite runs on a laptop without Docker in under a minute.
- **Good:** local tests still exercise the real client code path (Jedis, `EVALSHA`, `NOSCRIPT`
  fallback, connection pool) and real concurrent connections.
- **Cost:** no Spring features (configuration binding, actuator, metrics); routing, validation and
  JSON are hand-written and kept tiny. M2 may revisit this when the queue and database arrive.
- **Cost:** jedis-mock is slower than Valkey and runs LuaJ instead of Lua 5.1, so local numbers are
  not Valkey numbers, and a Valkey-only behaviour difference would show up only in CI. The README
  and results label which numbers come from which backend.
