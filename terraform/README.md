# Terraform — Papsukkal infrastructure

Provisions the GCS bucket that holds the sync-state file (`sync-state/last-sync.json`) and grants
the CronJob's Workload Identity service account (`application`) read/write on it. State is stored
in the `ent-gcs-tfa-papsukkal` GCS backend, one workspace per environment.

| Env | Project | Bucket |
|---|---|---|
| dev | `ent-papsukkal-dev` | `ror-papsukkal-dev` |
| tst | `ent-papsukkal-tst` | `ror-papsukkal-tst` |
| prd | `ent-papsukkal-prd` | `ror-papsukkal-production` |

The bucket names match `config.gcsStateBucket` in `helm/papsukkal/env/values-kub-ent-*.yaml`.

## Usage

```bash
cd terraform
terraform init
terraform workspace select kub-ent-dev   # or kub-ent-tst / kub-ent-prd
terraform plan  -var-file=env/dev.tfvars
terraform apply -var-file=env/dev.tfvars
```

> **Note:** `service_account` in each `env/*.tfvars` assumes the Workload-Identity-bound GCP service
> account is `application@<project>.iam.gserviceaccount.com`. Adjust if the infrastructure
> provisioner uses a different name.
