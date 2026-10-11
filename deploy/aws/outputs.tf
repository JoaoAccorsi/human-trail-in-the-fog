output "cloud_mobility_platform_url" {
  description = "Open in the browser (only from allowed_cidr)."
  value       = "http://${aws_instance.cmp.public_ip}:8501"
}

output "tms_url_inside_vpc" {
  description = "TMS address used by the Fog nodes and by the platform."
  value       = local.tms_url
}

output "tms_database" {
  description = "RDS endpoint of the TMS database (PostgreSQL + PostGIS), reachable only from inside the VPC."
  value       = "${aws_db_instance.tms.address}:${aws_db_instance.tms.port}/htf"
}

output "registry" {
  description = "ECR registry where deploy/aws/up.sh pushes the images."
  value       = local.registry
}

output "region" {
  value = var.region
}

output "edge_groups" {
  description = "Users of each Edge instance (htf-edge-1, htf-edge-2, ...)."
  value       = var.edge_groups
}

output "number_of_fogs" {
  value = var.number_of_fogs
}

output "edge_start_delay_s" {
  value = local.edge_start_delay_s
}

output "fog_private_ips" {
  value = local.fog_ip
}

output "results_bucket" {
  description = "S3 bucket where deploy/aws/collect-metrics.sh gathers the results of a run."
  value       = aws_s3_bucket.results.bucket
}

output "day" {
  value = var.day
}

output "ping_interval_ms" {
  value = var.ping_interval_ms
}
