# Papsukkal infrastructure: the GCS bucket that holds the sync-state file
# (sync-state/last-sync.json) and the IAM binding granting the CronJob's
# Workload Identity service account read/write access to it.

resource "google_storage_bucket" "state_bucket" {
  name                        = "${var.bucket_instance_prefix}-${var.bucket_instance_suffix}"
  project                     = var.storage_project
  location                    = var.location
  storage_class               = var.storage_class
  labels                      = var.labels
  force_destroy               = var.force_destroy
  uniform_bucket_level_access = true
  versioning {
    enabled = var.versioning
  }
}

# Placeholder for the sync-state prefix (GCS has no real directories).
resource "google_storage_bucket_object" "state_folder" {
  name    = "sync-state/"
  content = "Not really a directory, but it's empty."
  bucket  = google_storage_bucket.state_bucket.name
}

# Grant the application service account (Workload Identity) object read/write on the state bucket.
resource "google_storage_bucket_iam_member" "state_bucket_iam_member" {
  bucket = google_storage_bucket.state_bucket.name
  role   = var.service_account_bucket_role
  member = var.service_account
}
