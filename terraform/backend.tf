# Terraform state is stored in GCS. Select the environment with a workspace
# (e.g. `terraform workspace select kub-ent-dev`).
terraform {
  backend "gcs" {
    bucket = "ent-gcs-tfa-papsukkal"
  }
}
