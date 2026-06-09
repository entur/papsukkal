# Helm deployment

Papsukkal deploys as a GKE `CronJob` via this standalone chart, following the conventions of
sibling services (e.g. `kakka`): per-environment values under `env/`, credentials via
**External Secrets**, and `serviceAccountName: application` (the Workload-Identity SA the
infrastructure provisioner creates).

The chart is standalone (not the common chart) so it can set `spec.timeZone` (Europe/Oslo) and
`jobTemplate.spec.backoffLimit: 0`, which the common chart's cron template does not expose. The
labels/annotations/securityContext helpers in `templates/_helpers.tpl` mirror the common chart.

## Layout

```
helm/papsukkal/
  Chart.yaml
  values.yaml                      # defaults (image, resources, cron, config, secrets)
  env/
    values-kub-ent-dev.yaml        # per-environment config (env name, GCS, Tiamat/OAuth URLs)
    values-kub-ent-tst.yaml
    values-kub-ent-prd.yaml
  templates/
    cronjob.yaml                   # the sync CronJob (owns timeZone + backoffLimit)
    configmap.yaml                 # per-env application.properties, mounted into the job
    external-secret.yaml           # generic ExternalSecrets from .Values.secrets (e.g. Slack)
    external-secret-tiamat-oauth.yaml  # Tiamat OAuth client creds → Spring env names
    _helpers.tpl
```

## Configuration

Non-secret, per-environment config (`config.*` in the env values) is rendered into a ConfigMap
(`application.properties`), mounted at `/etc/application-config`, and overlaid on the baked
`application.yml` via `-Dspring.config.additional-location` (set in `JDK_JAVA_OPTIONS`). Structural
defaults stay in the jar's `application.yml`; secrets arrive as env vars from the External Secrets
(`envFrom`). This also points `logging.structured.format.console` at the custom
`no.entur.papsukkal.logging.GcpStructuredLogFormatter` (named by fully-qualified class — Boot 4 has
no built-in `gcp` format, only `ecs`/`gelf`/`logstash`) so deployed logs are Google-Cloud-Logging
structured JSON (local/test stay human-readable).

## Secrets

Credentials are synced from the namespace `SecretStore` (Google Secret Manager) by External Secrets
and consumed via `envFrom`:

- **`papsukkal-slack`** — `SLACK_URL` (same-name remote key; the app reads `${SLACK_URL}`).
- **`papsukkal-tiamat-oauth`** — the shared internal auth0 client, remote keys
  `MNG_AUTH0_INT_CLIENT_ID` / `MNG_AUTH0_INT_CLIENT_SECRET`, mapped to
  `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_TIAMAT_CLIENT_ID` / `_CLIENT_SECRET`.

## Transport security

`config.tiamatImportUrl` is plaintext `http://tiamat.<env>.entur.internal:80` (the same convention
as kakka/kingu). The bearer token + NeTEx body rely on the **service mesh** for in-cluster
encryption — no TLS is configured in this chart. Confirm the `papsukkal` namespace enforces mTLS
(`kubectl get peerauthentication -n papsukkal`); if it does not, change `tiamatImportUrl` to
`https://`. See CLAUDE.md › Transport security.

## Before deploying

In `env/values-kub-ent-<env>.yaml`, set `config.gcsStateBucket` (the only remaining placeholder —
`gcsProjectId` and the Tiamat/OAuth URLs are pre-filled per environment). Provision the
prerequisites first (CLAUDE.md › Open Items).

## Deploy

```bash
helm upgrade --install papsukkal helm/papsukkal \
  -n papsukkal \
  -f helm/papsukkal/values.yaml \
  -f helm/papsukkal/env/values-kub-ent-<env>.yaml
```

## Manual / force run

```bash
# normal manual run (still honours change detection)
kubectl create job --from=cronjob/papsukkal papsukkal-manual-$(date +%s)

# force a full resync AND override the validation gateway
# (under external versioning, BYPASS_VALIDATION authorizes Tiamat to delete missing zones)
kubectl create job --from=cronjob/papsukkal papsukkal-force-$(date +%s) --dry-run=client -o yaml \
  | kubectl set env --local -f - FORCE=true BYPASS_VALIDATION=true -o yaml | kubectl create -f -
```
