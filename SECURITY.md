# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security > Report a vulnerability** on this repository
(`https://github.com/EquinoxWN/flash-sale-engine/security/advisories/new`). Do not open a public issue.

Include what you found, how to reproduce it, and the impact you expect. You will get an answer
within 7 days. Fixes are released as soon as they are ready, and you are credited unless you ask
not to be.

## Supported versions

This project is pre-1.0. Only the latest commit on `main` receives fixes.

## Scope

- **In scope:** any way to sell more units than were loaded, to buy without a queue token or above
  the per-user limit, to make the gateway pass unvalidated input into a Valkey key, a script
  argument or a response, or to make it use unbounded memory or threads.
- **Out of scope:** impersonating a user through `X-User-Id`. M1 trusts that header by design
  (see the README); an authenticating proxy must set it.

## How this repository protects itself

- Every GitHub Action is pinned to a full commit SHA and the Valkey service image to a digest;
  workflows run with read-only permissions and without persisted credentials.
- Dependabot update pull requests are paused (`open-pull-requests-limit: 0`) so the repository
  keeps a single `main` branch; dependencies are updated by hand and checked by the CI audit.
- CI runs the format check, `-Xlint:all -Werror`, every test against jedis-mock and real Valkey,
  a 20,000-buyer burst whose oversell audit must pass, and a known-vulnerability check
  (OSV-Scanner on a CycloneDX SBOM of every resolved Maven dependency).
