# Papsukkal infrastructure: the GCS bucket that holds the sync-state file
# (sync-state/last-sync.json).
#
# Bucket access for the CronJob's Workload Identity service account (`application`) is granted by
# the infrastructure provisioner, not here — so this config does not manage a bucket IAM binding.

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
