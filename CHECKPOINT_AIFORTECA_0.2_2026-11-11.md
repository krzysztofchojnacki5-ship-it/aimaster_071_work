# AiFORTeca 0.2 — checkpoint 2026-11-11

## Repo state
- Repository: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- Current HEAD: `16db481e850ac829112eb9a3951503b8d9dc1e0a`

## Work completed
1. `0970e58973fc334343e3295fec179b60f262def8`: strict IPv6 literal validation in `FreeModelsCatalog.isLocalOllamaEndpoint`; only loopback, link-local and ULA `fc00::/7`, with scoped literals rejected.
2. `bc591054665113bacaf979d5ce7546bb569f94e6`: regression tests for fake `fc-*.example` / `fd-*.example`, valid private IPv6 ranges, public IPv6 rejection and scoped-literal rejection.
3. `52c62edead438615af457601e801ff1f019fc2e9`: cleartext policy now recognizes private IPv6 literals and IPv6 loopback.
4. `db79153af7f83cb20bfe859b67b9e64c8fddea6a`: explicit opt-in for HTTP Ollama on private IPv6, port 11434, allowed API paths, and no API key.
5. `f405fb6f1bbb8154a068279a195938c7270b83e1`: private IPv6 cleartext policy tests.
6. `018063bd21b3fc9c4418b14a1b24bfca2604d6d2`: provider endpoint IPv6 opt-in tests.
7. `c1dc85832c33eb424537d24039711ed12f889b5a`: normalize bracketed IPv6 host values in cleartext allowlist.
8. `16db481e850ac829112eb9a3951503b8d9dc1e0a`: bracketed IPv6 loopback regression test.
9. `59f7abc78925b033c78bb6d3b32e4cbac1539068`: initial checkpoint document (superseded by this update).

## CI evidence
- Run #463, Gradle Wrapper Validation: SUCCESS.
- Run #463, Android unit-test step: SUCCESS for commit `bc591054665113bacaf979d5ce7546bb569f94e6`.
- Run #463 was still reporting `Upload unit test reports` as IN_PROGRESS when last checked; release signing and APK assembly had not started.
- Important: run #463 tested the code through commit `bc591054...`, not the subsequent IPv6 cleartext-policy changes (commits 52c62e..16db48).
- For current HEAD `16db481...`, no CI run/status was exposed by the available commit-workflow lookup. Do not infer that current changes passed.
- No APK artifact has been confirmed or delivered. User requests APK only when explicitly asked.

## Next actions
1. Obtain CI evidence for current HEAD, including unit tests and `assembleRelease`.
2. If any test fails, inspect logs and fix the concrete cause before proceeding.
3. Audit endpoint validation call sites and ensure every network request passes through `ProviderEndpointPolicy`.
4. Continue model discovery/routing and token-budget integration after endpoint-security checks.
5. Never claim CI/device tests passed without evidence; do not send APK unless asked.

## Package ledger
- Source commits: `0970e589...`, `bc591054...`, `52c62ede...`, `db79153a...`, `f405fb6f...`, `018063bd...`, `c1dc8583...`, `16db481e...`.
- APK/build artifact: not yet confirmed for current HEAD.
