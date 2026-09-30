variable "region" {
  description = "AWS region where everything is created."
  type        = string
  default     = "us-east-1"
}

variable "allowed_cidr" {
  description = "Your public IP in CIDR form (e.g. 203.0.113.10/32): the only address allowed to open the Cloud Mobility Platform."
  type        = string
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

variable "edge_instance_type" {
  description = "EC2 type of each Edge simulator."
  type        = string
  default     = "t4g.micro"
}

variable "edge_users" {
  description = "YJMob100K users simulated by the Edges: one EC2 instance per user."
  type        = list(number)
  default     = [4000, 4005]
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
  description = "Seconds the Edges wait after boot, so the Chord ring can form first."
  type        = number
  default     = 120
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
