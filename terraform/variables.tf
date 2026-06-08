variable "storage_project" {
  description = "GCP project that holds the state bucket"
}

variable "kube_namespace" {
  description = "The Kubernetes namespace"
  default     = "papsukkal"
}

variable "labels" {
  description = "Labels used on all resources"
  type        = map(string)
  default = {
    manager = "terraform"
    team    = "ror"
    slack   = "talk-ror"
    app     = "papsukkal"
  }
}

variable "location" {
  description = "GCP bucket location"
  default     = "europe-west1"
}

variable "bucket_instance_prefix" {
  description = "Prefix for the bucket name (bucket names must be globally unique)"
  default     = "ror-papsukkal"
}

variable "bucket_instance_suffix" {
  description = "Per-environment suffix for the bucket name"
}

variable "force_destroy" {
  description = "When true, deleting the bucket also deletes its objects"
  default     = false
}

variable "storage_class" {
  description = "GCP storage class"
  default     = "STANDARD"
}

variable "versioning" {
  description = "Enable object versioning on the bucket"
  default     = false
}
