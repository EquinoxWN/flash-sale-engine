# Contributing

Thanks for helping. This is a Java project; issues and pull requests are welcome.

## Set up and check your change

Needs JDK 21+ with Maven. Caches stay in git-ignored folders inside the repo.

```bash
make setup   # resolve dependencies
make format  # apply palantir-java-format
make lint    # format check, compile with -Xlint:all -Werror
make test    # full test suite (set VALKEY_URL to also test against a real Valkey)
make demo    # burst of 20,000 buyers on the in-process server
make audit   # known-vulnerability check (as in CI)
```

A pull request is ready when `make lint` and `make test` pass and CI is green.

## Guidelines

- Keep each change focused; explain the problem it solves in the pull request.
- Every change to a Lua script needs a test of each result code it can return, and must keep
  `OversellTest` and the burst audit at zero oversold units.
- Keep scripts to basic Lua 5.1: they run on Valkey and on LuaJ (jedis-mock) in local tests.
- Add or update a test for every behaviour change, and update `docs/results/` when numbers change.
- Significant design changes need an ADR in `docs/adr/` (copy `0001` for the format).
- Keep function comments to one short phrase; let names and tests explain the rest.
- Report security problems privately, as described in [SECURITY.md](SECURITY.md).

By contributing you agree that your work is released under the [MIT License](LICENSE).
