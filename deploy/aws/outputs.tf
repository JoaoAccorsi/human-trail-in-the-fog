output "cloud_mobility_platform_url" {
  description = "Open in the browser (only from allowed_cidr)."
  value       = "http://${aws_instance.cmp.public_ip}:8501"
}

output "tms_url_inside_vpc" {
  description = "TMS address used by the Fog nodes and by the platform."
  value       = local.tms_url
}

output "registry" {
  description = "ECR registry where deploy/aws/up.sh pushes the images."
  value       = local.registry
}

output "region" {
  value = var.region
}

output "edge_users" {
  value = var.edge_users
}

output "fog_private_ips" {
  value = local.fog_ip
}
