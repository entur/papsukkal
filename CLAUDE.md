# Papsukkal

> Papsukkal — messenger of the gods. Fetches fare zone NeTEx data from Entur and delivers it to Tiamat.

Papsukkal is a lightweight Spring Boot **run-once batch job** that acts as a sync bridge between the Entur fare zone API and Tiamat (the internal stop place / fare zone register). It is deployed as a **Kubernetes CronJob**: on each scheduled run it checks the Entur API for a change, publishes the full NeTEx dataset to Tiamat when a change is detected, then exits. No always-on process, no web server.

---

## Open Items / Prerequisites

External dependencies and decisions that must be resolved before Papsukkal can run in production. These are ownership/provisioning items, not design gaps.

- [ ] **Tiamat `GroupOfTariffZones` fix deployed** — the default import endpoint must import both `FareFrame/fareZones` and `SiteFrame/groupsOfTariffZones` in one pass. Verified end-to-end on a local Tiamat; **must be merged and deployed to Papsukkal's target Tiamat environment** before go-live. (See *Target System — Tiamat*.)
- [ ] **OAuth2 client for Tiamat** — provision a machine-to-machine client-credentials client whose privileges permit importing fare zones; supply its credentials to Papsukkal as a secret. Does not exist yet. (See *Auth to Tiamat*.)
- [ ] **Confirm `importType`** with the Tiamat owners — `MERGE` is assumed for a full fare-zone republish; confirm and confirm import idempotency for an unchanged dataset. (See *Query parameters* / *Idempotency*.)
- [ ] **Tiamat host URL** for the target environment.
- [ ] **Slack incoming webhook** — create webhook + channel; supply URL as a secret (`papsukkal.slack.webhook-url`). (See *Notifications — Slack*.)
- [ ] **GCS state bucket** — create the bucket and grant the CronJob's Workload Identity service account `storage.objects.get` + `storage.objects.create`. (See *State Storage*.)

---

## Architecture

Papsukkal is a stateless sync bridge — no local data storage, no transformation, no validation. It is a pure messenger.

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
                             skip          ① notify "started" (Slack)
                            (silent)               │
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
- **Publisher** — POSTs the full NeTEx XML dataset to Tiamat's synchronous import endpoint (`POST /services/stop_places/netex`, `Content-Type: application/xml`, OAuth2 bearer token); a `2xx` confirms the import completed. Retries transient failures with backoff, fails fast on `4xx` (see *Error Handling, Retry & Notifications*)
- **SlackNotifier** — posts started / success / failure notifications to a Slack incoming webhook; delivery is best-effort and never fails the sync

There is **no scheduler component and no web layer** — Kubernetes triggers the run, and the process is run-once.

---

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| Framework | Spring Boot 3.x (run-once; `web-application-type=none`) |
| HTTP client | `RestClient` (Spring Boot built-in) |
| Scheduler | Kubernetes `CronJob` (daily, off-peak) |
| State storage | GCP Cloud Storage (single text file) |
| Deployment | GKE `CronJob` (run-once Job per tick) |
| Auth to GCP | Workload Identity |
| Auth to Tiamat | OAuth2 client-credentials (JWT bearer) |
| Notifications | Slack incoming webhook |
| Retry | Spring Retry (transient-only, exponential backoff) |

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

## State Storage — GCP Cloud Storage

Last sync state is stored as a plain text file in a GCP Cloud Storage bucket.

- **File:** `sync-state/last-export-path.txt`
- **Content:** The GCS object path (query string stripped) of the export published in the last successful sync — e.g. `/ent-gcs-fare-zone-netex-exporter-prd-001/all/v28/2026-01-01_open/all-farezones.xml`
- **Dependency:** `spring-cloud-gcp-starter-storage`
- **Auth:** Workload Identity on the CronJob's pod service account — no credentials to manage
- **Permissions needed:** `storage.objects.get` and `storage.objects.create` on the state bucket

First run (file does not exist) → treat as changed → trigger full sync.

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

The app reads a `FORCE` env var; when `true`, change detection is skipped and the dataset is published unconditionally. Useful for testing, forcing a resync after an incident, and initial setup validation.

> **Concurrency caveat:** an ad-hoc Job is *not* governed by the CronJob's `concurrencyPolicy`, so a manual run could overlap a scheduled one. Both paths are idempotent (Tiamat `MERGE` + last-writer-wins on the same export-path state file), so a concurrent double-run is a harmless duplicate import. If hard mutual exclusion is ever required, add a GCS object-generation **lease** around the publish — but that is likely overkill here.

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
| `importType` | `MERGE` (likely) | `MERGE` updates existing zones and adds new ones — the right steady-state mode when republishing the full set with stable Entur ids. Other modes: `INITIAL` (bulk first load, spawns parallel streams), `ID_MATCH`, `MATCH` (name/coordinate matching). **Confirm with the Tiamat owners** which mode they expect for a full fare-zone republish. |

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

### Auth to Tiamat

Tiamat is an OAuth2 resource server validating JWT bearer tokens (`spring.security.oauth2.resourceserver.jwt.issuer-uri`), and checks user authorization on import (toggled by Tiamat's `authorization.enabled`). So Papsukkal needs to:

- obtain a token via **OAuth2 client-credentials** from Entur's auth provider (a machine-to-machine client), and
- hold a client whose privileges permit editing/importing fare zones.

This is a real dependency that does not yet exist — a client must be provisioned, and its credentials supplied to Papsukkal (env/secret). Note this is **separate** from the GCP Workload Identity used for state storage and is unrelated to the Entur source API's `ET-Client-Name` header.

### Idempotency

Because state is written only after a successful publish (see *Change Detection*), a publish-succeeds-then-state-write-fails window will republish the same dataset on the next run. With `importType=MERGE` and stable Entur ids this is a safe no-op re-import, which is the intended behaviour — but it assumes Tiamat's import is idempotent for an unchanged dataset. Confirm this holds for the chosen `importType`.

---

## Error Handling, Retry & Notifications

### Publish retry

The Tiamat publish (and the upstream Entur fetch) distinguish **transient** from **fatal** failures:

| Class | Examples | Action |
|---|---|---|
| **Transient** | connection refused/reset, read timeout, HTTP `5xx`, HTTP `429` | Retry with exponential backoff + jitter — **3 attempts** total (e.g. base 5s, ×3, cap ~45s) |
| **Fatal** | HTTP `400` (validation), `401` / `403` (auth) | **No retry** — retrying can't help; alert immediately so config/credentials get fixed |

- **Client timeouts** must account for the synchronous import: a generous read timeout (e.g. connect 10s, **read 5 min**) on the `RestClient` request factory, since the response blocks until Tiamat finishes importing.
- **State is written only after a `2xx`.** On exhausted retries *or* a fatal `4xx`, `sync-state/last-export-path.txt` is left untouched, so the next daily run re-attempts the same export — the **CronJob schedule is the outer retry loop** (`backoffLimit: 0`, see *Scheduling & Concurrency*).
- **Run-once exit:** after Spring Retry is exhausted (or on a fatal `4xx`), the run emits the ❌ notification and the process **exits non-zero** so the Job is marked Failed and is visible in `kubectl get jobs` / alerting.
- Implementation: Spring Retry (`@Retryable` / `RetryTemplate`); `RestClient` has no built-in retry. Classify on `HttpClientErrorException` (fatal) vs `HttpServerErrorException` / `ResourceAccessException` (transient). The two retry layers are distinct: Spring Retry handles *within-run* transient blips; the daily CronJob handles *across-run* recovery.

### Notifications — Slack

A `SlackNotifier` posts to a Slack incoming webhook (URL supplied via secret, e.g. `papsukkal.slack.webhook-url`). Three events:

| Event | When | Payload |
|---|---|---|
| 🔄 **Started** | change detected, publish about to begin | trigger (`scheduled` / `manual`), new export path/version, previous path |
| ✅ **Success** | Tiamat returned `2xx` **and** state written | export path/version, duration, attempt count |
| ❌ **Failure** | retries exhausted, fatal `4xx`, or upstream Entur fetch failed | export path attempted, HTTP status / exception, attempt count, **note that state was NOT advanced (will retry next run)**, correlation id |

- **No notification on a no-change skip** — the common case stays silent to avoid noise; expose it as a metric / debug log only.
- **Delivery is best-effort:** a Slack failure is logged but never fails the sync. For ✅ success, the state write happens **before** the Slack post, so a Slack outage can never cause a republish.
- A manual ad-hoc Job emits the same three events, tagged `trigger=manual` (or `trigger=force` when `FORCE=true`).

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
