variable "region" {
  description = "AWS region where everything is created."
  type        = string
  default     = "us-east-1"
}

variable "allowed_cidr" {
  description = "Your public IP in CIDR form (e.g. 203.0.113.10/32): the only address allowed to open the Cloud Mobility Platform."
  type        = string
}

variable "number_of_fogs" {
  description = "Number of Fog nodes. It must be n*n with 200 % n == 0, so the 200x200 grid splits into equal square areas: 1, 4, 16, 25, 64, 100, 400 or 625 (one EC2 instance per node)."
  type        = number
  default     = 16

  validation {
    condition     = contains([1, 4, 16, 25, 64, 100, 400, 625], var.number_of_fogs)
    error_message = "The number_of_fogs must be one of 1, 4, 16, 25, 64, 100, 400, 625 (n*n with 200 % n == 0)."
  }
}

variable "fog_instance_type" {
  description = "EC2 type of each Fog node (Graviton/ARM64)."
  type        = string
  default     = "t4g.small"
}

variable "cloud_instance_type" {
  description = "EC2 type of the TMS and of the Cloud Mobility Platform."
  type        = string
  default     = "t4g.small"
}

variable "db_instance_class" {
  description = "RDS instance class of the TMS database (PostgreSQL + PostGIS, Graviton)."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "PostgreSQL version of the TMS database (major only: RDS picks the latest minor)."
  type        = string
  default     = "16"
}

variable "edge_instance_type" {
  description = "EC2 type of each Edge simulator (a t4g.small handles about 1000 users)."
  type        = string
  default     = "t4g.small"
}

variable "edge_groups" {
  description = "YJMob100K users simulated by the Edges: one EC2 instance per element, each element in HTF_USERS form (\"4000-5000\", \"4000,4005\" or a mix such as \"4000-4010,4500\")."
  type        = list(string)
  default     = ["4000,4005"]

  validation {
    condition = (length(var.edge_groups) >= 1 && length(var.edge_groups) <= 200 &&
    alltrue([for g in var.edge_groups : can(regex("^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$", g))]))
    error_message = "The edge_groups needs 1 to 200 elements like \"4000-5000\", \"4000,4005\" or \"4000-4010,4500\" (no spaces)."
  }
}

variable "day" {
  description = "YJMob100K day replayed by the Edges."
  type        = number
  default     = 20
}

variable "ping_interval_ms" {
  description = "Real time between two pings of an Edge (each ping is one 30-min timeslot)."
  type        = number
  default     = 1000
}

variable "edge_start_delay_s" {
  description = "Seconds the Edges wait after boot, so the Chord ring can form first. null = 120 + 2 per Fog node (152 s with 16 Fogs, 320 s with 100)."
  type        = number
  default     = null
}

variable "handover_policy" {
  description = "Handover decision of the Fog nodes: rsrp (RSRP + Kalman + TTT) or area (baseline)."
  type        = string
  default     = "rsrp"
}

variable "hom_db" {
  description = "Handover margin (HOM), in dB."
  type        = number
  default     = 3
}

variable "ttt_min" {
  description = "Time to Trigger (TTT), in minutes."
  type        = number
  default     = 60
}

variable "check_vcpu_quota" {
  description = "Stop the plan when the instances need more vCPUs than the account's quota for Running On-Demand Standard instances. Needs servicequotas:GetServiceQuota; set false to skip the check."
  type        = bool
  default     = true
}
