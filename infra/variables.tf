variable "region" {
  type    = string
  default = "us-east-1"
}

variable "project" {
  type    = string
  default = "franchises-api"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.project))
    error_message = "Use 3-31 lowercase letters, numbers and hyphens."
  }
}

variable "image_identifier" {
  type        = string
  default     = ""
  description = "Full ECR image URI, preferably pinned by digest. Empty provisions persistence and registry only."
}
