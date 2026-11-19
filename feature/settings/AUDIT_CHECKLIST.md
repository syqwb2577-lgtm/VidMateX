# Settings & security audit checklist (module-local, §10 / §5 / §9)

Scope: `feature/settings/src/main/.../security/`, `SettingsViewModel`, `SettingsMapper`,
`WhisperProbeUrlValidator`, `SettingsScreen`, and this module's tests. Hilt wiring in
`SettingsSecurityModule` was reviewed, not restructured.

Status labels follow `app/AUDIT_CHECKLIST.md`. No device here → Keystore/encrypt paths are
**IMPLEMENTED BUT NOT VERIFIED**; pure codec/readiness logic is JVM-tested.

## 1. Security control matrix (§10)

| Control | Wired? | Evidence | Status |
|---|---|---|---|
| Keystore AES/GCM encryption at rest (Whisper + resolver keys) | Yes | `AndroidKeystoreSecretCipher.encrypt/decrypt`; `AES/GCM/NoPadding`, 128-bit tag, 12–16 B IV | IMPLEMENTED BUT NOT VERIFIED (device crypto) |
| Values referenced by alias, never literal in code | Yes | `SecretAliases.RESOLVER_API_KEY / WHISPER_API_KEY`; `requireKnown` | COMPLETE AND VERIFIED (audit) |
| Secrets never logged / never in error strings | Verified by scan | No `Log`/`Timber`/`println` in module; `SecretCodecs.redact` returns length only; GCM decrypt failures degrade to `""` | COMPLETE AND VERIFIED (source scan) |
| Add flow | Yes | `SecretStore.add(alias, plain)`; VM `setResolverApiKey` / `setWhisperApiKey` persist encrypted | IMPLEMENTED BUT NOT VERIFIED |
| Rotate flow | Yes | `SecretStore.rotate(alias, newPlain)`; VM `rotateResolverApiKey` / `rotateWhisperApiKey` (non-blank guard) | IMPLEMENTED BUT NOT VERIFIED |
| Clear flow | Yes | blank token removes the DataStore row (`SettingsRepositoryImpl.writeSecrets`); VM `clearResolverApiKey` / `clearWhisperApiKey`; UI clear button | IMPLEMENTED BUT NOT VERIFIED |
| Codec helpers: base64 + IV/tag packing | Yes | `SecretCodecs.wrap/unwrap`, prefix `v1:`; IV-length byte first | COMPLETE AND VERIFIED (JVM) |
| Empty / unicode / tamper-feasible codec cases | Pure-tested | `SecretCodecsTest` (13 `@Test`): empty, unicode+emoji, structural well-formed, truncation, bad base64, bit-flip envelope note | COMPLETE AND VERIFIED (JVM) |
| Structural tamper sanity (pre-cipher) | Yes | `looksPacked(raw)`, `wellFormedToken(token)`; GCM auth itself stays device-side | COMPLETE AND VERIFIED (JVM shape) |

## 2. Settings model / validation matrix (§5 config)

| Control | Wired? | Evidence | Status |
|---|---|---|---|
| `AppSettings` snapshot mapping (model ↔ DataStore) | Yes | `SettingsMapper.toAppSettings/fromAppSettings`; blank normalization; enum fallbacks; junk quality → null | COMPLETE AND VERIFIED (JVM) |
| Whisper endpoint URL validation | Yes | `WhisperProbeUrlValidator` (https required; loopback http permitted); reused by readiness + live field check | COMPLETE AND VERIFIED (JVM; device network call pending) |
| Resolver base URL validation | Yes | `BackendUrlValidator.isValidHttpsBaseUrl`; shown live while typing (no test-run dependency) | IMPLEMENTED BUT NOT VERIFIED |
| Config completeness before enabling transcription | Yes | `TranscriptionReadiness.evaluate(endpoint, keyPresent, predicate)`; endpoint missing/invalid = blocking; key absent = warning | COMPLETE AND VERIFIED (JVM) |
| Non-secret fields in DataStore; secrets only in Keystore | Yes | Repository splits `writeNonSecrets` / `writeSecrets`; only token strings persisted for keys | COMPLETE AND VERIFIED (source audit) |
| Readiness exposed to UI/subtitles | Yes | `SettingsViewModel.whisperReadiness: StateFlow`; screen shows blocking reason and disables Test while blocking | IMPLEMENTED BUT NOT VERIFIED |

## 3. Settings screen control audit

| Control | Wired through VM? | Evidence |
|---|---|---|
| Theme / language / quality / embed mode dialogs | Yes | `setThemeMode`, `setAppLanguage`, `setPreferredQualityHeight`, `setEmbedMode` |
| Require Wi-Fi switch | Yes | `setRequireWifi` |
| Subtitle language field | Yes | `setSubtitleLanguageCode` |
| LibreTranslate URL + provider toggle | Yes | `setLibreUrl`, `setTranslationProvider` |
| Resolver URL field (live invalid hint) | Yes | `BackendUrlValidator` + test result; supporting text switches to error |
| Resolver key password field | Yes | `setResolverApiKey`; clear key button → `clearResolverApiKey` |
| Resolver test / Retest button | Yes | `testResolverConnection`; states idle/loading/ok/error(invalid/unreachable) |
| Whisper endpoint field (live invalid hint) | Yes | `WhisperProbeUrlValidator`; readiness blocking text |
| Whisper key password field | Yes | `setWhisperApiKey`; clear key button → `clearWhisperApiKey` |
| Whisper Test button | Yes | enabled only when no blocking issue; `testWhisperConnection` |
| Whisper status row | Yes | readiness-driven icon/text (missing/invalid/key-warning/configured) |
| Clear-key buttons | Yes | visible only when a key exists; blank write removes the row |
| No visual-only placeholder states | Verified | Every switch/button reads a VM state and calls a VM method; static cards are informational headers only |

## 4. Test inventory (this module)

| File | `@Test` count | Notes |
|---|---:|---|
| `security/SecretCodecsTest.kt` | 13 | original 7 + 6 added (unicode round-trip; well-formed packed; legacy/plain rejected; truncation; bad base64; bit-flip envelope note) |
| `SettingsMapperTest.kt` | 12 | original 9 + 3 added (partial snapshot defaults; validation left to checker; negative/junk quality → null) |
| `WhisperProbeUrlValidatorTest.kt` | 5 | unchanged, pure JVM |
| `WhisperReadinessTest.kt` (new) | 7 | missing/invalid/blocking; valid+no-key warning; loopback http; fully ready |

Added/modified `@Test` count for this module: **16** (6 + 3 + 7); existing tests preserved.

## 5. Remaining / blocked

- Device: Keystore cipher encrypt/decrypt round-trip; actual encrypted row in DataStore;
  rotation over existing token; clear deletes row.
- `feature/subtitles` gating on `whisperReadiness` (cross-module request, app checklist §5.3).
- Network probes (`testResolverConnection`, `testWhisperConnection`) execute real OkHttp
  calls only on device/emulator with connectivity.
