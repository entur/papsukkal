# Terraform — Papsukkal infrastructure

Provisions the GCS bucket that holds the sync-state file (`sync-state/last-sync.json`). State is
stored in the `ent-gcs-tfa-papsukkal` GCS backend, one workspace per environment.

Bucket **access** for the CronJob's Workload Identity service account (`application`) is granted by
the infrastructure provisioner — this config does **not** manage a bucket IAM binding.

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
