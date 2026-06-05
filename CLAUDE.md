# Papsukkal

> Papsukkal — messenger of the gods. Fetches fare zone NeTEx data from Entur and delivers it to Tiamat.

Papsukkal is a lightweight Spring Boot **run-once batch job** that acts as a sync bridge between the Entur fare zone API and Tiamat (the internal stop place / fare zone register). It is deployed as a **Kubernetes CronJob**: on each scheduled run it checks the Entur API for a change, publishes the full NeTEx dataset to Tiamat when a change is detected, then exits. No always-on process, no web server.

---

## Open Items / Prerequisites

External dependencies and decisions that must be resolved before Papsukkal can run in production. These are ownership/provisioning items, not design gaps.

- [ ] **Tiamat `GroupOfTariffZones` fix deployed** — the default import endpoint must import both `FareFrame/fareZones` and `SiteFrame/groupsOfTariffZones` in one pass. Verified end-to-end on a local Tiamat; **must be merged and deployed to Papsukkal's target Tiamat environment** before go-live. (See *Target System — Tiamat*.)
- [ ] **OAuth2 client for Tiamat** — provision a machine-to-machine client-credentials client whose privileges permit importing fare zones; supply its credentials to Papsukkal as a secret. Does not exist yet. (See *Auth to Tiamat*.)
- [ ] **Confirm `importType`** with the Tiamat owners — `MERGE` (or Tiamat's configured default) is assumed for a full fare-zone republish. Note the prune-on-missing behaviour is governed by `externalVersioning`, *not* by `importType` (tracked separately above); this item is only about which mode Tiamat expects and confirming re-import of an unchanged delivery is idempotent. (See *Query parameters* / *Idempotency*.)
- [ ] **Tiamat host URL** for the target environment.
- [ ] **Slack incoming webhook** — create webhook + channel; supply URL as a secret (`papsukkal.slack.webhook-url`). (See *Notifications — Slack*.)
- [ ] **GCS state bucket** — create the bucket and grant the CronJob's Workload Identity service account `storage.objects.get` + `storage.objects.create`. (See *State Storage*.)
- [ ] **Confirm Tiamat external-versioning config** — verify the target Tiamat has `fareZone.externalVersioning=true` / `groupOfTariffZones.externalVersioning=true`. This determines that imports are full-replace-with-prune, which is *why* the validation gateway is a hard prerequisite. (See *Full replace under external versioning* / *Validation Gateway*.)
- [ ] **Tune validation thresholds** — set `papsukkal.validation.*` floors and `max-shrink-pct` to the known-good magnitude of the production export (≈485 zones / 29 groups today). A `> 0` floor is unsafe — the small-but-nonzero delivery is the dangerous case. (See *Validation Gateway*.)

---

## Architecture

Papsukkal is a stateless sync bridge — no local data storage, no transformation. It is a pure messenger, with **one guardrail**: a *validation gateway* sanity-checks the downloaded dataset before publishing. This guardrail exists because Tiamat imports fare zones with **external versioning** — a full replace that *prunes any zone/group absent from the delivery* — so a corrupt or partial upstream export would delete live data in Tiamat. The gateway inspects but never mutates, so the messenger role holds. (See *Validation Gateway*.)

```
K8s CronJob (daily)     ─┐
                          ├──▶ FareZoneSyncService ──▶ FareZoneApiClient ──▶ Entur API (NeTEx XML)
ad-hoc Job (kubectl)     ─┘              │
   [FORCE bypasses                       ▼
    change detection]            Change detector
                                 (export path compare)
                                         │
                               ┌─────────┴─────────┐
                           no change             changed
                               │                   │
                             skip         download NeTEx XML body
                            (silent)               │
                                          Validation gateway
                                    (counts + refs vs last-good baseline)
                                                   │
                                       ┌───────────┴───────────┐
                                     pass                     fail
                                       │                       │
                            ① notify "started" (Slack)  ③ notify "failure"
                                       │              (Slack; state NOT advanced)
                                Publisher ──▶ Tiamat (POST)
                              (retry transient / fail fast 4xx)
                                       │
                           ┌───────────┴───────────┐
                      2xx + state write        exhausted / 4xx
                           │                       │
                  ② notify "success"      ③ notify "failure"
                      (Slack)               (Slack; state NOT advanced)
```

### Components

- **SyncRunner** — `ApplicationRunner` entrypoint: invokes `FareZoneSyncService` once on startup, then exits the JVM with `0` on success / non-zero on failure. Reads the `FORCE` env var to optionally bypass change detection
- **FareZoneSyncService** — orchestrates the full sync flow for a single run
- **FareZoneApiClient** — HTTP client for the Entur API; handles `ET-Client-Name` auth header. Reads the `302 → GCS` `Location` header with redirects disabled (for the change check), and follows it to download the raw NeTEx XML only when a publish is needed
- **Change detector** — compares the GCS export path (redirect `Location` with query string stripped) from the current fetch to the last stored path; if identical, sync is skipped
- **DatasetValidator** — sanity-checks the downloaded NeTEx XML before it is published: structural checks (parse-valid, every group `TariffZoneRef` resolves to a `FareZone` in the delivery) and count-drift checks (`FareZone` / `GroupOfTariffZones` counts vs the last-good baseline). On failure it aborts the publish, leaves state untouched, emits ❌, and exits non-zero. Bypassable via `BYPASS_VALIDATION` (see *Validation Gateway*)
- **Publisher** — POSTs the full NeTEx XML dataset to Tiamat's synchronous import endpoint (`POST /services/stop_places/netex`, `Content-Type: application/xml`, OAuth2 bearer token); a `2xx` confirms the import completed. Retries transient failures with backoff, fails fast on `4xx` (see *Error Handling, Retry & Notifications*)
- **SlackNotifier** — posts started / success / failure notifications to a Slack incoming webhook; delivery is best-effort and never fails the sync

There is **no scheduler component and no web layer** — Kubernetes triggers the run, and the process is run-once.

---

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Java 25 |
| Build | Maven (`spring-boot-starter-parent`) |
| Framework | Spring Boot 4.x (run-once; `web-application-type=none`) |
| HTTP client | `RestClient` (Spring Boot built-in) |
| Scheduler | Kubernetes `CronJob` (daily, off-peak) |
| State storage | GCP Cloud Storage (single JSON file) |
| Deployment | GKE `CronJob` (run-once Job per tick) |
| Auth to GCP | Workload Identity |
| Auth to Tiamat | OAuth2 client-credentials (JWT bearer) |
| Notifications | Slack incoming webhook |
| Retry | Spring Framework native `@Retryable` (`org.springframework.resilience`), transient-only, exponential backoff |

No Apache Camel — the sync flow is a straight line and does not warrant route DSL overhead.

---

## Source API — Entur Fare Zone NeTEx Export

- **Endpoint:** `GET https://api.entur.io/distance/netex/fare-zones`
- **Auth:** `ET-Client-Name` header (required) — format: `<company>-<application>`
- **Behaviour:** Returns `302` redirect to a GCS bucket URL containing a NeTEx XML export
- **Optional params:** `organisationId` (int) to filter by organisation; `time` (datetime) for point-in-time export
- **Docs:** https://beta.developer.entur.no/apis/prdudisapi/netex#export-fare-zones-as-netex

---

## Change Detection

The Entur API responds with a `302` whose `Location` header is a **GCS V4 signed URL**. The query string (`X-Goog-Date`, `X-Goog-Signature`, `X-Goog-Credential`, `X-Goog-Expires`) is regenerated on **every request**, so the *full URL* is never stable and must not be compared. The **object path**, however, is stable and semantically versioned:

```
https://storage.googleapis.com/ent-gcs-fare-zone-netex-exporter-prd-001/all/v28/2026-01-01_open/all-farezones.xml?X-Goog-...
└──────────────────────── compare this (path, query stripped) ──────────────────────────────────────────────┘ └ ignore ┘
                                                              │      │   │        └─ validity window
                                                              │      │   └─ export version — bumps on change (v28 → v29)
                                                              │      └─ org scope (`all` when no organisationId filter)
                                                              └─ bucket
```

The `v28` version segment (and the validity-window segment) change exactly when Entur publishes a new export, which is precisely the signal we want.

1. On each sync run, fetch the Entur API endpoint with **HTTP redirects disabled** and read the `Location` header
2. Strip the query string to get the **export path** (everything before `?`)
3. Compare to the path stored in GCP Cloud Storage from the previous sync
4. If paths match → data has not changed → skip publishing (no body download needed)
5. If paths differ (or no previous state exists) → follow the URL to download the NeTEx XML body, then publish the full dataset to Tiamat
6. After a successful publish, write the new export path back to GCP Cloud Storage

This makes change detection cheap: an unchanged dataset is detected from the redirect alone, without ever downloading the (multi-MB) NeTEx body.

> No response-body hashing is used. The versioned path changes deterministically on every new export, so a SHA-256 fallback would add a full body download on every poll for no benefit. (Revisit only if Entur ever overwrites a dataset in place at an unchanged path.)

---

## Validation Gateway

After a change is detected and the NeTEx XML is downloaded, but **before** it is published, Papsukkal runs a `DatasetValidator` sanity check. A failed check aborts the publish exactly like a fatal error — state is **not** advanced, ❌ is emitted, the process exits non-zero — so the next daily tick re-checks and proceeds automatically once the upstream export is healthy again.

### Why this is a safety interlock, not a nice-to-have

Papsukkal's target Tiamat is configured with **external versioning** for fare data:

```properties
fareZone.externalVersioning=true
groupOfTariffZones.externalVersioning=true
```

Under external versioning the import is a **full replace, not an additive merge**: Tiamat prunes any zone/group that is *absent* from the delivery. The relevant Tiamat logic:

```java
// With external versioning the import is a full replace: prune FareZones not present in this delivery.
if (fareZoneConfig.isExternalVersioning() && !fareFrameZoneIds.isEmpty()) {
    int deletedCount = fareZoneSaverService.deleteAllExcept(fareFrameZoneIds);
    logger.info("External versioning cleanup: deleted {} orphaned FareZones", deletedCount);
}
```

So if Entur ever publishes a broken/partial export — say an upstream bug emits 12 zones instead of 485 — a single unattended POST **deletes the other 473 live zones**. The gateway is the mechanism that makes a daily, unattended, full-replace sync safe to run at all.

> **Note the `!fareFrameZoneIds.isEmpty()` guard.** Tiamat self-protects the *empty* delivery (zero zones → cleanup skipped). It does **not** protect the *small-but-nonzero* delivery — 1 zone deletes 484. The worst-case input is therefore precisely the one Tiamat won't catch, which is why a `> 0` floor is useless and the checks below must compare against a meaningful magnitude.

### Checks

Counts are extracted with a **streaming StAX reader** (`XMLStreamReader`) over the already-downloaded bytes — O(1) memory, no JAXB/DOM unmarshal. One pass counts `FareZone`, `GroupOfTariffZones`, and `TariffZoneRef` start-elements and collects FareZone ids + group member refs for the resolution check.

**Tier 1 — structural (no baseline needed):**
- XML parses as a valid `PublicationDelivery`
- `FareZone` count ≥ a meaningful **absolute floor** (e.g. ≥ 400) — *not* `> 0`; that gap is already covered by Tiamat, and the small-nonzero case is the dangerous one
- `GroupOfTariffZones` count ≥ floor (e.g. ≥ 25)
- **Every group `TariffZoneRef` resolves to a `FareZone` defined in the delivery** — a partial export is exactly how dangling group members arise, and pre-checking locally yields a clearer error than a Tiamat reject

**Tier 2 — count drift vs last-good baseline (the "significantly lower" check):**
- Fail if `fareZoneCount < prev * (1 − threshold)` — **percentage drop**, not absolute delta, so it scales. Strict and **fail-closed** (e.g. > 5–10% shrink), because under external versioning every missing zone is real data loss.
- Same drift check on `groupCount` — groups are externally versioned and pruned independently.
- **Growth never blocks** — only shrinkage is suspect.
- **No baseline** (first run, or state absent) → Tier 1 floors only.

The baseline counts come from the **last successfully published** run, stored in the GCS state file (see *State Storage*) — Papsukkal-owned, written only after a `2xx`, so it is always "last known good".

### Bypass — `BYPASS_VALIDATION`

A genuine fare-zone reduction *will* eventually trip the drift check, and the only way through is an explicit override. The app reads a `BYPASS_VALIDATION` env var, **separate from `FORCE`** (which only bypasses change detection) to keep the semantics clean:

- `FORCE=true` → skip change detection
- `BYPASS_VALIDATION=true` → skip the sanity gateway

Under external versioning, setting `BYPASS_VALIDATION=true` explicitly means **"I authorize Tiamat to delete the zones missing from this delivery."** A bypass run logs and Slack-tags this loudly (`trigger=force` / a `bypass=true` flag), publishes, and writes the new (lower) counts as the baseline — so subsequent normal runs resume cleanly against the new magnitude.

### Config

Floors and thresholds live in config, not code:

```properties
papsukkal.validation.fare-zone.min-count=400
papsukkal.validation.group.min-count=25
papsukkal.validation.max-shrink-pct=10
```

---

## State Storage — GCP Cloud Storage

Last sync state is stored as a small JSON file in a GCP Cloud Storage bucket. It records both the export path (for change detection) and the published counts (the last-good baseline for the *validation gateway*).

- **File:** `sync-state/last-sync.json`
- **Content:** the export path (query string stripped) and the counts of the export published in the last successful sync:
  ```json
  {
    "exportPath": "/ent-gcs-fare-zone-netex-exporter-prd-001/all/v28/2026-01-01_open/all-farezones.xml",
    "fareZoneCount": 485,
    "groupCount": 29,
    "memberCount": 639,
    "publishedAt": "2026-01-01T03:00:00Z"
  }
  ```
- **Dependency:** `spring-cloud-gcp-starter-storage`
- **Auth:** Workload Identity on the CronJob's pod service account — no credentials to manage
- **Permissions needed:** `storage.objects.get` and `storage.objects.create` on the state bucket
- **Written only after a `2xx`** — so the stored counts are always "last known good", which is exactly the baseline the validation gateway compares against.

First run (file does not exist) → treat as changed → trigger full sync; with no baseline, the validation gateway applies Tier 1 absolute floors only.

---

## Scheduling & Concurrency

Papsukkal runs as a Kubernetes `CronJob`. There is no in-app scheduler or lock — the platform owns both.

- **Cadence:** daily, off-peak — e.g. `schedule: "0 3 * * *"` with `timeZone: "Europe/Oslo"`. Fare zones change rarely and propagation is not time-critical; the manual trigger covers urgent updates. (The change check is cheap, so the cadence can be raised later with negligible cost.)
- **Overlap guard:** `concurrencyPolicy: Forbid` — if a previous run is somehow still active, the new tick is skipped. This replaces the in-process lock a long-running service would need. With a daily cadence and short runs, overlap is effectively impossible anyway.
- **Lifecycle:** the process is **run-once** — `FareZoneSyncService` executes one sync, then the JVM exits. Exit `0` = success (published or correctly skipped); non-zero = failure.
- **K8s retry:** `backoffLimit: 0` is recommended — in-run transient retries are handled by Spring Retry (see below), and the *next daily tick* is the natural outer retry. A non-zero `backoffLimit` would re-run the whole sync and re-fire ❌ notifications, so avoid it unless you want that.
- **History:** keep `successfulJobsHistoryLimit` / `failedJobsHistoryLimit` small (e.g. 3) for debuggable Job logs without clutter.

### Manual trigger / force resync

No HTTP endpoint — a manual run is an **ad-hoc Job** created from the CronJob:

```bash
# normal manual run (still honours change detection)
kubectl create job --from=cronjob/papsukkal papsukkal-manual-$(date +%s)

# force a full resync regardless of change detection
kubectl create job --from=cronjob/papsukkal papsukkal-force-$(date +%s) --dry-run=client -o yaml \
  | kubectl set env --local -f - FORCE=true -o yaml | kubectl create -f -
```

The app reads a `FORCE` env var; when `true`, change detection is skipped and the dataset is published unconditionally. Useful for testing, forcing a resync after an incident, and initial setup validation. A second flag, `BYPASS_VALIDATION=true`, skips the *validation gateway* — required when a genuine fare-zone reduction would otherwise trip the count-drift guard. **Under external versioning, `BYPASS_VALIDATION=true` authorizes Tiamat to delete the zones missing from the delivery** — use deliberately. The two flags are independent; a clean-slate forced resync of a known-shrunk dataset sets both.

```bash
# force a resync AND accept the validation gateway's verdict being overridden
kubectl create job --from=cronjob/papsukkal papsukkal-force-$(date +%s) --dry-run=client -o yaml \
  | kubectl set env --local -f - FORCE=true BYPASS_VALIDATION=true -o yaml | kubectl create -f -
```

> **Concurrency caveat:** an ad-hoc Job is *not* governed by the CronJob's `concurrencyPolicy`, so a manual run could overlap a scheduled one. Both paths are idempotent (re-importing the same full delivery prunes nothing even under external versioning + last-writer-wins on the same-export-path state file), so a concurrent double-run is a harmless duplicate import. If hard mutual exclusion is ever required, add a GCS object-generation **lease** around the publish — but that is likely overkill here.

---

## Target System — Tiamat

Tiamat is the internal stop place and fare zone register. After a change is detected, Papsukkal POSTs the full NeTEx XML dataset to Tiamat's NeTEx import endpoint.

- **Repo:** https://github.com/entur/tiamat

### Import endpoint

```
POST /services/stop_places/netex
Content-Type: application/xml
Authorization: Bearer <jwt>

<raw NeTEx PublicationDelivery XML body — the bytes downloaded from the GCS export>
```

The import is **synchronous**: Tiamat unmarshals and imports the `PublicationDelivery` inline and responds with the modified NeTEx (each zone annotated with its assigned NSR id). A `2xx` therefore confirms the import actually *completed*, not merely that it was queued — there is no separate job to poll. Large datasets are handled by Tiamat spawning parallel streams internally; the HTTP response still blocks until the import finishes, so the client timeout must be generous.

(Tiamat also has async *export* resources, but import is sync-only — there is no async import endpoint to target.)

### Query parameters

| Param | Value | Why |
|---|---|---|
| `fareZoneFrameSource` | *(omit)* | **Not needed.** The default import path now imports both the `FareFrame/fareZones` and the `SiteFrame/groupsOfTariffZones` in one pass (see below). Do **not** set `FARE_FRAME` — that routes to the FareFrame-only importer, which skips the groups. |
| `importType` | `MERGE`, or omit to use Tiamat's configured default | `MERGE` updates existing zones and adds new ones. **Crucially, the additive/destructive behaviour is governed by Tiamat's `externalVersioning` config, not by `importType`** — with `fareZone.externalVersioning=true` the import is a *full replace that prunes missing zones* regardless of mode (see *Full replace under external versioning* and *Validation Gateway*). Other modes: `INITIAL`, `ID_MATCH`, `MATCH`. **Confirm with the Tiamat owners** the expected mode for a full republish. |

Example:

```bash
curl -XPOST -H 'Content-Type: application/xml' -H "Authorization: Bearer $TOKEN" \
  -d @all-farezones.xml \
  'https://<tiamat-host>/services/stop_places/netex?importType=MERGE'
```

### Fare zones + tariff-zone groups import in one pass

The export is split across two frames:

```
FareFrame
  └ fareZones            → 485 FareZone            (zone definitions)
SiteFrame
  └ groupsOfTariffZones  → 29  GroupOfTariffZones  (each groups several fare zones)
```

Each `GroupOfTariffZones` lists its members as `<TariffZoneRef ref="…:FareZone:N"/>` — the `ref`s point at the **FareZone ids defined in the FareFrame** (the element is named `TariffZoneRef` only because `FareZone` is a NeTEx subtype of `TariffZone`). So the groups and the zones they reference live in different frames.

The default Tiamat import path imports **both**: it loads the `FareFrame` fare zones and the `SiteFrame` groups in a single POST, validating each group member's `FareZone` ref against the just-imported fare zones. No `fareZoneFrameSource` param is required.

> **Verified end-to-end** against a running Tiamat by POSTing the real export (no `fareZoneFrameSource`):
> from a baseline of 0/29 export groups present, a single POST produced **29/29 groups**, **639 group members** (matching the 639 `TariffZoneRef`s in the export), with **all 639 members resolving to an existing fare zone (0 unresolved)**, while the **485 fare zones** were imported/re-versioned in the same call.
>
> **History:** earlier Tiamat could not do this — `FARE_FRAME` imported the zones but silently dropped the groups, and `SITE_FRAME` imported nothing (the export's `SiteFrame` has no `tariffZones`, which gated both the zone importer and `GroupOfTariffZonesImportHandler`). This was fixed in Tiamat by extending the import path to validate group members against the FareFrame's fare zones. **Papsukkal depends on that fix being deployed** to its target Tiamat environment — until then, the groups will not import.

### Full replace under external versioning

The target Tiamat is configured with `fareZone.externalVersioning=true` and `groupOfTariffZones.externalVersioning=true`. Under external versioning the import is a **full replace**: after importing the delivery's zones, Tiamat prunes every fare zone (and group) **not** present in this delivery (`deleteAllExcept(fareFrameZoneIds)`). It does *not* prune when the delivery is empty (`!fareFrameZoneIds.isEmpty()` guard), but it *does* prune for any non-empty-but-partial delivery.

This is the intended steady-state behaviour for a full republish — Entur's export is authoritative, so zones it drops should be removed — but it also means **a corrupt/partial upstream export deletes live data**. That risk is precisely what the *Validation Gateway* guards against; the gateway is a hard prerequisite for running this sync unattended.

### Auth to Tiamat

Tiamat is an OAuth2 resource server validating JWT bearer tokens (`spring.security.oauth2.resourceserver.jwt.issuer-uri`), and checks user authorization on import (toggled by Tiamat's `authorization.enabled`). So Papsukkal needs to:

- obtain a token via **OAuth2 client-credentials** from Entur's auth provider (a machine-to-machine client), and
- hold a client whose privileges permit editing/importing fare zones.

This is a real dependency that does not yet exist — a client must be provisioned, and its credentials supplied to Papsukkal (env/secret). Note this is **separate** from the GCP Workload Identity used for state storage and is unrelated to the Entur source API's `ET-Client-Name` header.

### Idempotency

Because state is written only after a successful publish (see *Change Detection*), a publish-succeeds-then-state-write-fails window will republish the same dataset on the next run. Re-importing the identical full delivery is a safe no-op even under external versioning — `deleteAllExcept` over the same id set prunes nothing — so with stable Entur ids this is idempotent, which is the intended behaviour. Confirm this holds for the chosen `importType`.

---

## Error Handling, Retry & Notifications

### Publish retry

The Tiamat publish (and the upstream Entur fetch) distinguish **transient** from **fatal** failures:

| Class | Examples | Action |
|---|---|---|
| **Transient** | connection refused/reset, read timeout, HTTP `5xx`, HTTP `429` | Retry with exponential backoff + jitter — **3 attempts** total (e.g. base 5s, ×3, cap ~45s) |
| **Fatal** | HTTP `400` (validation), `401` / `403` (auth) | **No retry** — retrying can't help; alert immediately so config/credentials get fixed |

- **Client timeouts** must account for the synchronous import: a generous read timeout (e.g. connect 10s, **read 5 min**) on the `RestClient` request factory, since the response blocks until Tiamat finishes importing.
- **State is written only after a `2xx`.** On exhausted retries, a fatal `4xx`, *or* a failed validation check, `sync-state/last-sync.json` is left untouched, so the next daily run re-attempts the same export — the **CronJob schedule is the outer retry loop** (`backoffLimit: 0`, see *Scheduling & Concurrency*).
- **Run-once exit:** after Spring Retry is exhausted (or on a fatal `4xx`), the run emits the ❌ notification and the process **exits non-zero** so the Job is marked Failed and is visible in `kubectl get jobs` / alerting.
- Implementation: Spring Retry (`@Retryable` / `RetryTemplate`); `RestClient` has no built-in retry. Classify on `HttpClientErrorException` (fatal) vs `HttpServerErrorException` / `ResourceAccessException` (transient). The two retry layers are distinct: Spring Retry handles *within-run* transient blips; the daily CronJob handles *across-run* recovery.

### Notifications — Slack

A `SlackNotifier` posts to a Slack incoming webhook (URL supplied via secret, e.g. `papsukkal.slack.webhook-url`). Three events:

| Event | When | Payload |
|---|---|---|
| 🔄 **Started** | change detected, publish about to begin | trigger (`scheduled` / `manual`), new export path/version, previous path |
| ✅ **Success** | Tiamat returned `2xx` **and** state written | export path/version, duration, attempt count |
| ❌ **Failure** | **validation gateway rejected the dataset**, retries exhausted, fatal `4xx`, or upstream Entur fetch failed | export path attempted, HTTP status / exception **or which validation check failed with specifics** (e.g. `FareZone count 12 is 97.5% below baseline 485, threshold 10%`), attempt count, **note that state was NOT advanced (will retry next run)**, correlation id |

- **No notification on a no-change skip** — the common case stays silent to avoid noise; expose it as a metric / debug log only.
- **Validation-failure ❌ fires *before* the 🔄 started event** — the gateway runs before publish begins, so a rejected dataset never emits 🔄. Make the failure message specific (new vs baseline counts, % drop, which check) so the alert is actionable.
- **Delivery is best-effort:** a Slack failure is logged but never fails the sync. For ✅ success, the state write happens **before** the Slack post, so a Slack outage can never cause a republish.
- A manual ad-hoc Job emits the same events, tagged `trigger=manual` (or `trigger=force` when `FORCE=true`); a run with `BYPASS_VALIDATION=true` additionally carries a `bypass=true` flag so an authorized-deletion publish is unmistakable in the channel.

### Failure-notification noise

With a daily cadence and `backoffLimit: 0`, a sustained Entur or Tiamat outage fires ❌ at most **once per day** — generally acceptable, so no extra dedup is needed up front. (If the cadence is later raised, revisit: alert only on the **first failure after a success**, or **rate-limit** ❌.) Keep 🔄 / ✅ unconditional since real changes are rare.

---

## Naming Convention

This service follows the Babylonian/Mesopotamian deity naming convention used across the platform (Tiamat, Marduk, Anshar, Kakka, Nusku). Papsukkal is the Akkadian messenger god — fitting for a service whose sole purpose is to carry data between systems.

---

## Related Services

| Service | Role |
|---|---|
| Tiamat | Target system — stop place / fare zone register |
| Entur Distance & Zones API | Source of fare zone NeTEx data |
