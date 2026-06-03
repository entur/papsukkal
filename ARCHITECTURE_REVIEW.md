# Papsukkal — Architecture Review Report

*Produced by a five-specialist multi-agent review of `CLAUDE.md`: one reviewer per architecture aspect, four rounds of cross-aspect debate, then a final ratification round. Every claim that a resource is publicly accessible was proven by constructing the URL and issuing a real HTTP request (see §4). Outcome: **unanimous consensus (5/5), review concluded, no hard blockers.***

---

## 1. Executive Summary

**Overall verdict: the architecture is sound and well-matched to its workload, but it is not yet production-ready.** Papsukkal's core design choices — a stateless run-once Kubernetes CronJob, change detection via the stable query-stripped GCS object path, publish-then-write-state ordering, and a clean two-layer retry model (within-run Spring Retry + the daily tick as the outer loop) — are all defensible and were repeatedly validated against live systems and Tiamat source during the review. The live Entur 302→signed-URL contract behaves exactly as documented, and the version segment has demonstrably advanced (v28 → v42), empirically confirming the change-detection premise.

The blocking issues are concentrated at the integration boundary with Tiamat and in operational hardening that the doc currently leaves unstated. They are largely *prerequisite/provisioning* and *guard-rail* gaps rather than design flaws. The four genuinely-contested design choices were put to a binding vote and resolved **unanimously** (see §5).

**Top risks (in priority order):**

1. **Two un-verified Tiamat preconditions cause a day-one silent failure either way (HIGH).** Tiamat's `enabledImportTypes` defaults to `ID_MATCH` only — if the target environment has not whitelisted `MERGE`, every publish returns a fatal `400 "ImportType: MERGE not enabled!"` and Papsukkal stalls permanently. Independently, if the un-deployed `GroupOfTariffZones` import fix is not on the target, the omit-`fareZoneFrameSource` path returns a perfectly valid `2xx` while importing **zero groups**. Both must be verified on a *pinned* target Tiamat version, not against `master`.

2. **A `2xx` does not robustly confirm a correct import (HIGH).** Papsukkal advances its state file on the raw HTTP status. A structurally-valid but zero-group import returns `2xx`, so the broken baseline is recorded permanently and the corrected data is never re-attempted (no-change skips are silent). This is the most dangerous, asymmetric failure mode in the system. **Ratified mitigation:** a required post-publish non-zero count gate (Item B).

3. **No pod resource limits / `-Xmx` against the measured ~8.4 MB export (HIGH).** The export is exactly 8,392,122 bytes; it is buffered in memory on the way up and Tiamat returns an annotated NeTEx body in memory on the way back. With no sized memory limit a run-once JVM OOMKills (exit 137) mid-import, silently losing the daily run under `backoffLimit: 0`.

4. **No `activeDeadlineSeconds` / request-write timeout; durable failure signal can vanish (HIGH).** The 5-minute *read* timeout covers neither a stalled ~8.4 MB upload nor a post-`200` body-read hang. A stuck pod can outlive its budget and, under `concurrencyPolicy: Forbid`, silently suppress the next tick. With best-effort Slack plus `failedJobsHistoryLimit: 3`, a sustained failure can leave **zero durable evidence after 3 days** — there is no wired "no successful run in N hours" alert.

5. **GCS state-write permissions and atomicity are under-specified (MEDIUM).** `storage.objects.get` + `create` is not guaranteed sufficient to *overwrite* `last-export-path.txt`; a failing second write makes change detection report "changed" forever and republish 8.4 MB to a privileged endpoint daily. **Ratified mitigation:** an `ifGenerationMatch` conditional write (Item A).

---

## 2. Per-Aspect Findings

### 2.1 Change Detection & State Storage

**Strengths.** The premise was verified live and held across all four rounds: the 302 `Location` is a GCS V4 signed URL whose object path is stable while the signature/`X-Goog-Date` query regenerates per request, so comparing the query-stripped path is the correct invariant. Version drift (v28 → v42) empirically proved the "version bumps on change" claim. The cheap-skip optimization (detect no-change from the redirect alone, never downloading the multi-MB body) is genuine and valuable. Publish-then-write-state is the correct at-least-once failure bias; first-run-as-changed is the right bootstrap; the no-SHA-256 decision is well-reasoned for the steady state.

**Risks.**
- **In-place overwrite at an unchanged path is undetectable (MEDIUM).** If Entur ever re-exports corrected content under the same path without a version bump, path comparison reports no-change and the fix never reaches Tiamat — a silent stale-data failure, with no notification because skips are silent.
- **org-scope / time params would corrupt path semantics (MEDIUM).** `organisationId` changes the `/all/` segment and `time` changes the validity-window date; either breaks the version-based invariant. Papsukkal MUST call with neither — this needs to be an explicit, enforced invariant.
- **Signed-URL expiry mid-run (LOW→addressed).** TTL is 900s (15 min). Reusing the change-check redirect for a slow download can lapse the window and surface a `403` that the original table wrongly classified as fatal.
- **State file has no validation/atomicity guarantee (LOW→MEDIUM).** A truncated/poisoned path or a lost update between overlapping writers can cause a perpetual-republish or stuck loop.

**How the debate refined the take.** The owner **conceded** that the original "GCS `create` silently overwrites" assertion was overstated, moving to an **`ifGenerationMatch` write with `412` treated as a benign concurrent-writer no-op** (which also closes the lost-update window reachable when ad-hoc Jobs bypass `concurrencyPolicy: Forbid`) — **ratified as Item A**. The owner **elevated** "advance state on raw `2xx`" from a purely-safe at-least-once choice to a MEDIUM-HIGH correctness risk, adopting a **post-publish non-zero count gate** as the precondition for writing state — **ratified as Item B**. The standalone in-place-overwrite mitigation softened from a per-poll HEAD/ETag tiebreaker to a **periodic FORCE republish + staleness alert** (gated on `MERGE` being whitelisted+idempotent). The pre-store **host/path-shape assertion** was promoted from defense-in-depth to a mandatory change-detection-integrity step.

### 2.2 External Integration Contracts (Entur source + Tiamat import)

**Strengths.** Both contracts were characterized with unusual rigor and largely confirmed: the Entur 302→V4-signed-URL path behaves as documented (live `v42/2026-02-01_open`, `X-Goog-Expires=900`), and Tiamat's `ImportResource.java` confirms `@Path("netex")`, `@Consumes(APPLICATION_XML)`, default `frameSource=SITE_FRAME` (the full-import branch), and default `importType=MERGE`. The warning against `fareZoneFrameSource=FARE_FRAME` is correct. The synchronous-import consequence (generous read timeout because the response blocks until import completes) is correctly derived. The un-deployed groups fix is correctly tracked as a hard prerequisite with concrete verification numbers (29/29 groups, 639/639 members, 485 zones).

**Risks.**
- **`enabledImportTypes` defaults to `ID_MATCH` only — `MERGE` may be rejected with a fatal `400` (HIGH).** Verified in source (default `ID_MATCH` at line 77; `BAD_REQUEST "ImportType: <x> not enabled!"` at line 108). This is the single most likely day-one stall.
- **NeTEx frame-shape / schema drift is unguarded under "pure messenger" (MEDIUM).** A cross-frame contract (groups in `SiteFrame` referencing `FareZone` ids in `FareFrame`) means a layout change can produce either a fatal `400` or a silent zero-group `2xx`.
- **Contract verified only against `master`, not a pinned deployed artifact (MEDIUM).** Default dispatch and the groups-validation fix can differ by version.
- **`ET-Client-Name "required"` is contradicted by the live API (LOW).** The endpoint returns `302` with or without it — it is attribution/quota, not access control.
- **Doc's signed-URL example is stale (LOW).** `v28/2026-01-01` vs live `v42/2026-02-01` (harmless; confirms version advances).

**How the debate refined the take.** The "`2xx` precedes import completion" mechanism was **corrected**: source shows the import completes synchronously *before* `Response.ok(...)` is built and `RuntimeException`s re-throw as `5xx`, so the real residual is a **valid-but-empty (zero-group) `2xx`** plus a possible mid-stream body failure after the `200` header. The owner **claimed ownership** of the single named **go-live gate** (FORCE-test on the pinned target: `MERGE` whitelisted; omit-param path imports both frames with non-zero groups; `MERGE` idempotent/no-churn) and split "poison export" into a config-`400` (Tiamat-config fix) versus a malformed-dataset case, concluding a **dead-letter helps neither** for a verbatim-forwarding single-tenant messenger. The owner also agreed the non-zero count assertion is a **contract check at the integration boundary, not business validation** — within Papsukkal's remit (ratified Item B).

### 2.3 Error Handling, Retry & Notifications

**Strengths.** A clean two-layer retry model (Spring Retry within-run; the daily CronJob with `backoffLimit: 0` as the outer retry, deliberately avoiding re-fired notifications). "State written only after `2xx`" is the right durability primitive. Transient/fatal classification maps concretely onto `RestClient` exception types. The 5-minute read timeout is correctly tied to the synchronous import. Slack ordering (state-write before success-post) correctly ensures a Slack outage can never cause a republish, and failures still surface as a non-zero Job exit.

**Risks.**
- **`2xx` does not confirm import success (HIGH).** Surviving mechanisms: a logically-valid zero-group `2xx`, and a `StreamingOutput` body failure *after* the `200` header is committed (a `200`-then-truncated-body that a status-keyed table can misread as success).
- **No partial-import handling / escalation (MEDIUM, downgraded from HIGH).** A consistently-failing export leaves state correctly unadvanced — the daily tick is the intended retry; the missing piece is escalation, not a queue.
- **Slack outage masks failure to humans (MEDIUM).** Best-effort Slack + `failedJobsHistoryLimit: 3` + no wired Job-failure alert can lose a sustained failure after 3 days.
- **`429` ignores `Retry-After` (LOW); token-TTL-vs-retry-chaining 401 (LOW).** A late retry attempt (>10 min post-token-mint) could present an expired JWT and hit a `401`.

**How the debate refined the take.** The owner **downgraded** the dead-letter item to MEDIUM and reframed remediation as a count-gate + staleness alert + alert-on-Nth-consecutive-failure. **Tempered** the "non-zero Job exit is a second signal" strength to "necessary but insufficient," promoting the **"no successful run in N hours" alert to a REQUIRED control.** **Split** `403` handling host-scoped (storage.googleapis.com `403` = transient/expired-URL; Tiamat-host `403` = fatal/auth). **Withdrew** the "transient-once-401" idea in favor of a **TTL sanity-check** keeping a Tiamat-host `401` unconditionally fatal — **ratified as Item C**. **Committed** to a notification payload that surfaces the Tiamat 4xx body + effective `importType`, with redaction.

### 2.4 Deployment & Runtime Model (K8s CronJob, run-once)

**Strengths.** Run-once CronJob is correctly matched to a rarely-changing, non-time-critical dataset; rejecting an in-app scheduler/web layer/lock is well justified. The concurrency model is coherent (`Forbid` for scheduled overlap, `backoffLimit: 0`, small history limits). The ad-hoc-Job-bypasses-`concurrencyPolicy` hole is named and (conditionally) neutralized via idempotency. FORCE via `kubectl` keeps the run-once model intact and inherits RBAC/audit.

**Risks.**
- **No pod resource requests/limits or `-Xmx` against the measured 8,392,122-byte export (HIGH).** Resident XML can approach ~2× plus JVM overhead; an OOMKill (exit 137) silently loses the daily run.
- **No `activeDeadlineSeconds` / `startingDeadlineSeconds`; no request-write timeout (HIGH).** A stuck pod can hang far longer than the design implies and suppress the next tick under `Forbid`.
- **Observability of a short-lived run-once pod is under-designed (MEDIUM).** Pull-based scrapes miss the pod; the no-change case is silent with no metrics sink; no absence-of-success alert.
- **JVM cold-start cost is unbudgeted (LOW); FORCE overlap doubles notifications / evicts history slots (LOW).**

**How the debate refined the take.** The owner **committed Deployment to own** the absence-of-success / N-consecutive-Failed-Job alert and the FORCE pre-flight mechanics, and **hardened the go-live gate to a continuously-detected condition** (the target Tiamat can drift after ship) — **ratified as Item D**. Held firm that **"overlap is harmless" must be stated conditionally** (gated on `MERGE` whitelisted+idempotent — default is `ID_MATCH`). Narrowed token handling to a **TTL sanity-check**, explicitly opposing per-attempt re-mint (cold-start tax) and transient-once-401 — **ratified as Item C**.

### 2.5 Authentication, Secrets & Data Exposure

**Strengths.** Three independent, appropriately-scoped credential planes: Workload Identity for GCS (no static keys), `ET-Client-Name` for Entur, OAuth2 client-credentials JWT for Tiamat — and the doc is explicit they are distinct. Data-at-rest gating verified: the bucket is private; the dataset is only retrievable via the short-lived signed URL; storing only the query-stripped path correctly avoids persisting a live credential. Client-credentials is the right grant for an unattended batch job; `401/403` correctly fatal.

**Risks.**
- **Token acquisition entirely unspecified (MEDIUM).** No issuer-uri, audience, scope, or token-fetch error classification.
- **Least-privilege GCS grant likely insufficient for the rewrite path (MEDIUM).** `get`+`create` may fail to overwrite — a stuck-state daily 8.4 MB republish to a privileged endpoint.
- **Secret handling named but not specified (MEDIUM).** Plain env-var injection exposes the OAuth secret and Slack webhook in `kubectl describe pod` and heap dumps; no rotation specified. The Slack webhook is itself a bearer-like capability.
- **`ET-Client-Name` is not an access gate (LOW); the signed URL is downloadable by anyone holding it (LOW); TLS/endpoint-trust expectations unstated (LOW); FORCE/ad-hoc Jobs widen who can trigger a privileged write (LOW).**

**How the debate refined the take.** The owner **conceded** that mid-import token expiry is a non-issue for the run-once model (downgraded to a TTL sanity-check, ratified Item C). **Strengthened** the GCS least-privilege finding and **closed the fact dispute** with Change Detection, preferring `ifGenerationMatch`/`412` because it keeps the SA least-privileged (no `objectAdmin`/delete) *and* closes the lost-update race (ratified Item A). **Coupled** a body-surfacing + redaction requirement (surfacing the Tiamat 4xx body for diagnosability increases the leak surface into the lower-trust Slack channel, so both must land together) and drew an explicit link between the OOM-heap-dump and plain-env-var secret-exposure paths.

---

## 3. Cross-Cutting Tensions: Resolved & Open

| Tension | Resolution status |
|---|---|
| **The two Tiamat preconditions (`MERGE` whitelisted AND groups-fix deployed) on a *pinned* target, not `master`** | **RESOLVED (Item D = D2, 5–0).** A single named go-live gate, enforced **both** as a FORCE pre-flight (Integration owns the contract assertion; Deployment owns the mechanics) **and** continuously in production (per-run count gate + config-400/host-scoped-403 alert), because the target Tiamat is independently re-deployable and can silently drift. |
| **Does a `2xx`-but-empty import need a per-run response count gate, given the "pure messenger, no validation" stance?** | **RESOLVED (Item B = B1, 5–0).** Send `skipOutput=false`; after a `2xx`, run a constant-memory StAX scan asserting **non-zero** `FareZone` AND `GroupOfTariffZones` before advancing state. Agreed to be a contract check at the integration boundary, not the transformation/validation the "pure messenger" stance forbids. |
| **GCS state-write IAM/atomicity** | **RESOLVED (Item A = A2, 5–0).** `ifGenerationMatch` conditional write with `412` treated as a benign concurrent-writer no-op — keeps the SA least-privileged (no `objectAdmin`/delete) and closes the lost-update window opened when ad-hoc/FORCE Jobs bypass `concurrencyPolicy: Forbid`. Confirm empirically against the target bucket/GSA. |
| **Token-TTL-vs-retry-chaining `401`** | **RESOLVED (Item C = C1, 5–0).** TTL sanity-check: mint once, require token TTL > `activeDeadlineSeconds`; a Tiamat-host `401` stays unconditionally fatal. Per-attempt re-mint and transient-once-401 both rejected. |
| **Signed-URL expiry `403` vs the `403=FATAL` retry table** | **Resolved in principle:** host-scoped — storage.googleapis.com `403` = transient (re-fetch fresh URL before download); Tiamat-host `403` = fatal/auth. State must record the path actually downloaded-and-published (re-fetch version-skew constraint). Accepted in the uncontested bundle (P1). |
| **"Overlap is a harmless duplicate import"** | **Re-scoped to conditional:** safe only if `MERGE` is whitelisted AND idempotent AND the state write is atomic (now guaranteed by Item A). Not unconditional. |
| **Periodic FORCE republish as a safety net** | **Converged:** must be gated on `MERGE` whitelisted+idempotent; mitigates in-place overwrite **by unconditional re-import, not detection**; is **orthogonal to** (not a replacement for) the per-run count gate (Item B). Staleness alert is the PRIMARY always-on backstop. |
| **Notification payload: body-surfacing + redaction + host-scoped 403** | **Resolved (uncontested bundle, accepted 5/5):** surface the Tiamat 4xx body (whitelisted short strings) + effective `importType`, with hard redaction of `Authorization`, the full signed `Location` query string, and token material across logs and Slack; treat the webhook as a rotatable credential. |
| **Secret injection mechanism + OOM heap-dump exposure link** | **Resolved (uncontested bundle, accepted 5/5):** mounted files / external secret operator, not plain env vars; document rotation. |
| **`200`-then-truncated-body classification** | **Resolved (uncontested bundle, accepted 5/5):** a distinct FAILED/transient classification (state not advanced), separate from the read-timeout/`activeDeadlineSeconds` controls. |

---

## 4. Verified Public-Accessibility Claims

Per the review's evidence rule, **every** claim that a resource is publicly accessible was proven by constructing the URL and issuing a real HTTP request via `curl`; the resulting status code is recorded below. A `302`/`200`/`401-challenge` is not a reachability error; a `403`/`401-listing` denial *disproves* public accessibility.

| # | Claim | URL | HTTP status | Public? |
|---|---|---|---|---|
| 1 | Entur fare-zones endpoint returns `302` **with** `ET-Client-Name` (reachable, redirect not followed) | `https://api.entur.io/distance/netex/fare-zones` | `302` | Yes (reachable) |
| 2 | Same endpoint returns `302` **without** `ET-Client-Name` (header is **not** an access gate) | `https://api.entur.io/distance/netex/fare-zones` | `302` | Yes (reachable) |
| 3 | `302` `Location` is a GCS V4 signed URL; live path `v42/2026-02-01_open`; `X-Goog-Expires=900`; query (`X-Goog-Date/Signature/Credential/Expires`) regenerates per request | `https://storage.googleapis.com/ent-gcs-fare-zone-netex-exporter-prd-001/all/v42/2026-02-01_open/all-farezones.xml?X-Goog-...` | `302` (Location inspected) | n/a (redirect) |
| 4 | Following the redirect delivers the NeTEx body; **measured 8,392,122 bytes, `application/xml`** | `https://api.entur.io/distance/netex/fare-zones` (followed with `-L`) | `200` (8,392,122 bytes) | Yes, via signed URL only |
| 5 | The signed URL itself is downloadable by an **unauthenticated** client holding it (no GCP creds) while its 900s window is open | captured signed `Location` | `200` | Yes (capability URL) |
| 6 | **GCS export object is NOT public** — unsigned/query-stripped object path is gated (bucket private) | `https://storage.googleapis.com/ent-gcs-fare-zone-netex-exporter-prd-001/all/v42/2026-02-01_open/all-farezones.xml` | `403` | **No (gated)** |
| 7 | **Bucket root listing is NOT public** | `https://storage.googleapis.com/ent-gcs-fare-zone-netex-exporter-prd-001/` (and the JSON list API `…/storage/v1/b/…/o`) | `403` (root) / `401` (list API) | **No (gated)** |
| 8 | Tiamat GitHub repo is publicly reachable | `https://github.com/entur/tiamat` | `200` | Yes |
| 9 | Tiamat `ImportResource.java` is publicly fetchable; confirms `@Path("netex")`, default `frameSource=SITE_FRAME`, default `importType=MERGE`, `enabledImportTypes` default `ID_MATCH`, `BAD_REQUEST "ImportType: <x> not enabled!"`, `skipOutput`→empty `Response.ok().build()` else streamed body | `https://raw.githubusercontent.com/entur/tiamat/master/src/main/java/org/rutebanken/tiamat/rest/netex/publicationdelivery/ImportResource.java` | `200` | Yes (`master`) |
| 10 | Entur developer docs for the NeTEx fare-zones export reachable | `https://beta.developer.entur.no/apis/prdudisapi/netex#export-fare-zones-as-netex` | `200` | Yes |

**Reachability summary.**
- **Publicly reachable (no credentials):** the Entur endpoint (returns a `302` regardless of `ET-Client-Name`); the **signed URL** while its 900s window is open (anyone holding the URL can download the full export — it is a capability URL); the Tiamat GitHub repo and `ImportResource.java`; the Entur developer docs.
- **Gated/private (public access disproven):** the GCS export **object path** (`403` unsigned, #6) and the **bucket root listing** (`403`/`401`, #7). The data is private at rest and only reachable via Entur's short-lived signed URL. This empirically confirms the change-detection design's security premise.
- **`master` vs the deployed Tiamat:** every favourable Tiamat behaviour (default `SITE_FRAME` full import, default `MERGE`, one-pass groups, the import-types config) was confirmed only against `master` (#9). The target environment runs a specific image; the import-types config and the groups-validation fix can differ by version — which is exactly why the go-live gate is both pre-flight and continuous (Item D).

---

## 5. Consensus & Ratified Decisions

After four debate rounds converged on substance but kept items flagged "pending formal ratification," a final ratification round put the four genuine choice-points to a binding vote. **All four carried unanimously (5–0); all five reviewers accepted the ten-item uncontested bundle; no reviewer recorded a hard blocker; the review is concluded.**

| Item | Question | Decision | Vote | One-line rationale |
|---|---|---|---|---|
| **A** | GCS state-write mechanism | **A2 — `ifGenerationMatch` conditional write; `412` = benign no-op** | 5–0 | Fixes the overwrite gap at least privilege (no `objectAdmin`/delete) *and* closes the lost-update race that ad-hoc/FORCE Jobs open by bypassing `concurrencyPolicy: Forbid`. |
| **B** | Post-publish correctness gate vs "pure messenger" | **B1 — REQUIRED non-zero `FareZone` AND `GroupOfTariffZones` StAX count gate before advancing state** | 5–0 | A zero-group `2xx` would silently pin a broken export as "last good" forever; a constant-memory count assertion is a boundary contract check, not the validation the messenger stance forbids. |
| **C** | Token `401` on a late (retry-chained) attempt | **C1 — TTL sanity-check (mint once, TTL > `activeDeadlineSeconds`); Tiamat-host `401` unconditionally fatal** | 5–0 | Removes the only legitimate late-401 cause at zero per-attempt cost; re-mint pays a cold-start tax for no gain and transient-once-401 would mask real credential failures. |
| **D** | Go-live gate scope | **D2 — pre-flight PLUS continuous in-production detection** | 5–0 | The target Tiamat is independently re-deployable and can silently drift off the groups-fix or de-whitelist `MERGE`, so a one-time pre-flight cannot keep stored state trustworthy. |

**Uncontested bundle (accepted 5/5 as binding controls):** pod resource limits + `-Xmx` sized for the 8,392,122-byte body; `activeDeadlineSeconds` + request-write timeout + `startingDeadlineSeconds`; a durable "no successful run in N hours" / N-consecutive-Failed-Job alert; the mandatory pre-store `Location` host/path-shape assertion + no-`organisationId`/no-`time` invariant; re-fetch-fresh-signed-URL-before-download + host-scoped `403` retry rule + record-the-path-actually-published; the redacted failure-notification payload; secret injection via mounted files/external secret operator + rotation; token acquisition specifics (issuer-uri/audience/scope, auth-server `5xx` transient / `401` fatal); a clarified Tiamat host network exposure + no-TLS-verification-disabled invariant; and the `200`-then-truncated-body distinct FAILED classification.

**Remaining open disagreements: none.** Consensus is unanimous and the review is concluded. The only residual *actions* are external verifications (the empirical GCS-write confirmation against the target bucket/GSA, and the go-live gate against the pinned target Tiamat), not unresolved design questions.

---

## 6. Prioritized Recommendations

### P0 — Go-live blockers (verify on the *pinned* target Tiamat, not `master`)
1. **Establish the named go-live gate via a FORCE test POST** against the pinned target Tiamat version, confirming: (a) `MERGE` is in `netex.import.enabled.types`; (b) the omit-`fareZoneFrameSource` default path imports **both** `FareFrame/fareZones` and `SiteFrame/groupsOfTariffZones` with a non-zero group count in the response; (c) `MERGE` is idempotent / no-churn on an unchanged re-import. Integration owns the assertion; Deployment owns the mechanics. **Make it continuous, not one-time** (Item D = D2).
2. **Gate state-advance on import correctness, not the raw status code (Item B = B1).** Send `skipOutput=false`; after a `2xx`, fully consume the body and run a constant-memory StAX/SAX scan asserting **non-zero** `FareZone` AND `GroupOfTariffZones` (not fixed counts) before writing `last-export-path.txt`. Treat a body-read truncation/IOException after the `200` header as a FAILED (transient) publish — state not advanced.
3. **Set pod resource requests/limits and an explicit `-Xmx`** sized against ~8.4 MB × ~2 resident + JVM overhead + the StAX scan footprint, so the run-once JVM cannot OOMKill mid-import.
4. **Set `activeDeadlineSeconds` and an explicit request-write timeout** (the 5-min read timeout covers neither the upload nor a post-`200` hang); add `startingDeadlineSeconds`.
5. **Wire a durable "no successful run in N hours" / N-consecutive-Failed-Job alert** (e.g. `kube_cronjob_status_last_successful_time` / `kube_job_status_failed`), independent of Slack.
6. **Adopt the `ifGenerationMatch` GCS state-write (Item A = A2)** — treat `412` as a benign concurrent-writer no-op — and confirm the write succeeds against the actual target bucket/GSA. (This closes both overwrite-semantics and the lost-update window; a broader verb grant is the rejected alternative.)
7. **Provision the Tiamat OAuth2 client-credentials client** scoped to fare-zone import only; specify issuer-uri (matching Tiamat's `resourceserver.jwt.issuer-uri`), audience, scope, and token-fetch error classification (auth-server `5xx` transient / `401` fatal). **Assert token TTL > `activeDeadlineSeconds`** (Item C = C1); keep a Tiamat-host `401` unconditionally fatal.

### P1 — Correctness & robustness guards
8. **Re-fetch a fresh signed URL immediately before the body download** (never reuse the change-check redirect); classify `403` host-scoped (storage.googleapis.com = transient/re-fetch; Tiamat host = fatal/auth); record the path **actually downloaded-and-published** to avoid version skew. Commit this `403` change to the retry table.
9. **Add a mandatory pre-store host/path-shape assertion** (`host == storage.googleapis.com` AND `/<bucket>/all/v<N>/<date>_<state>/all-farezones.xml`); treat a non-conforming `Location` as a fatal upstream-contract failure (notify, do not advance state). Pin the **no-`organisationId` / no-`time`** invariant, enforced by the same check.
10. **Commit the failure-notification payload spec:** surface the Tiamat 4xx body (whitelisted short strings) + effective `importType` + host-scoped `403` classification, with redaction of `Authorization`, the full signed `Location` query string, and token material across both logs and Slack. Treat the Slack webhook as a rotatable credential.
11. **Inject secrets via mounted files / an external secret operator (GSM/Vault), not plain env vars** (avoids exposure in `kubectl describe pod` and OOM heap dumps); document rotation; scope namespace RBAC for who may create ad-hoc/FORCE Jobs.

### P2 — Operational hardening & accuracy
12. **Keep the go-live gate continuously detected** (Item D): the per-run count gate catches a later reverted groups-fix; the config-400/host-scoped-403 alert catches a later `MERGE` de-whitelisting (the target Tiamat can drift after ship).
13. **Add a gated periodic FORCE republish** (e.g. monthly) as a *secondary* backstop against in-place overwrite — enabled only after the go-live gate is green, documented as mitigating by unconditional re-import (not detection), and paired with the count gate. The staleness alert remains the primary backstop.
14. **State the "overlap is harmless" claim conditionally** (gated on `MERGE` whitelisted+idempotent + atomic state write, now provided by Item A). Defer the GCS object-generation lease unless hard mutual exclusion is later required.
15. **Honor `Retry-After` on `429`**; add a durable metrics/log path (push-gateway/OTel) for the short-lived pod so successful no-change runs leave a queryable signal.
16. **Correct the doc:** `ET-Client-Name` is attribution/quota (not "required"/"auth"); refresh the stale `v28/2026-01-01` example to note the version advances; state a no-TLS-verification-disabled invariant for all three outbound calls and clarify the Tiamat host's network exposure.

---

## Appendix — Review Method

- **Five specialist reviewers**, one per aspect: Change Detection & State Storage, External Integration Contracts, Error Handling/Retry/Notifications, Deployment & Runtime Model, Authentication/Secrets/Data Exposure.
- **Four rounds of cross-aspect debate**, each reviewer reading all peers' findings, endorsing and challenging across cross-cutting tensions, and independently re-verifying contested URL claims with `curl`.
- **One ratification round** turning the four genuine choice-points into binding majority votes — all carried 5–0.
- **Evidence rule:** no claim of public accessibility was accepted without a constructed URL and an observed non-error HTTP status; denials (`403`/`401`) were recorded as disproof. See §4.
- **Outcome:** unanimous consensus, review concluded, zero hard blockers; residual actions are external verifications, not open design questions.
