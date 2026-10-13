# HTF on AWS: number_of_fogs Fog nodes (16 by default) + TMS + Cloud Mobility Platform +
# Edge simulators (one per element of edge_groups, each replaying many users), one EC2
# instance each, in a single VPC, and the TMS database in Amazon RDS for PostgreSQL (with
# PostGIS). Every instance runs one Docker container pulled from ECR; the logs go to
# CloudWatch Logs (group /htf). No SSH: use SSM.

data "aws_caller_identity" "me" {}

data "aws_availability_zones" "available" {
  state = "available"
}

# Latest Amazon Linux 2023 for ARM64 (Graviton)
data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  registry  = "${data.aws_caller_identity.me.account_id}.dkr.ecr.${var.region}.amazonaws.com"
  log_group = "/htf"

  # Fixed private addresses in the /20 subnet (the Fog nodes advertise theirs on the Chord ring):
  #   10.20.0.5 TMS, 10.20.0.6 platform, 10.20.0.(10+i) Edge i (up to 200),
  #   Fog N at host 256+N: 10.20.1.N for N <= 255, then 10.20.2.x ... (up to 625 nodes).
  subnet_cidr = "10.20.0.0/20"
  tms_ip      = cidrhost(local.subnet_cidr, 5)
  cmp_ip      = cidrhost(local.subnet_cidr, 6)
  fog_ip      = { for n in range(1, var.number_of_fogs + 1) : tostring(n) => cidrhost(local.subnet_cidr, 256 + n) }
  edge = {
    for i, users in var.edge_groups : tostring(i + 1) => {
      users = users
      ip    = cidrhost(local.subnet_cidr, 10 + i)
    }
  }

  seed_url = "http://${local.fog_ip["1"]}:9001"
  tms_url  = "http://${local.tms_ip}:8080"

  # The ring takes longer to converge with more nodes: by default 120 s + 2 s per Fog node.
  edge_start_delay_s = coalesce(var.edge_start_delay_s, 120 + 2 * var.number_of_fogs)
}

# ------------------------------------------------------------------ network
resource "aws_vpc" "htf" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "htf" }
}

resource "aws_internet_gateway" "htf" {
  vpc_id = aws_vpc.htf.id
  tags   = { Name = "htf" }
}

# One public subnet: the instances need outbound internet to pull images from ECR.
# Inbound traffic is restricted by the security groups below.
resource "aws_subnet" "htf" {
  vpc_id                  = aws_vpc.htf.id
  cidr_block              = local.subnet_cidr
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true
  tags                    = { Name = "htf" }
}

resource "aws_route_table" "htf" {
  vpc_id = aws_vpc.htf.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.htf.id
  }
  tags = { Name = "htf" }
}

resource "aws_route_table_association" "htf" {
  subnet_id      = aws_subnet.htf.id
  route_table_id = aws_route_table.htf.id
}

# RDS asks for subnets in two availability zones. The database runs in the zone of the
# instances (subnet htf); this second, private subnet (no route to the internet) only completes
# the subnet group.
resource "aws_subnet" "db_b" {
  vpc_id            = aws_vpc.htf.id
  cidr_block        = "10.20.16.0/24"
  availability_zone = data.aws_availability_zones.available.names[1]
  tags              = { Name = "htf-db-b" }
}

# All HTF nodes talk freely to each other (MQTT 190x, HTTP 900x, TMS 8080) and to nothing else inbound.
resource "aws_security_group" "nodes" {
  name        = "htf-nodes"
  description = "HTF nodes: traffic only between members of this group"
  vpc_id      = aws_vpc.htf.id

  ingress {
    description = "any traffic between HTF nodes"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# The database only accepts the HTF nodes (in practice, the TMS) on the PostgreSQL port.
resource "aws_security_group" "db" {
  name        = "htf-db"
  description = "TMS database (RDS PostgreSQL) from the HTF nodes"
  vpc_id      = aws_vpc.htf.id

  ingress {
    description     = "PostgreSQL from the HTF nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.nodes.id]
  }
}

# The Cloud Mobility Platform is the only public entry point, and only for your IP.
resource "aws_security_group" "cmp_public" {
  name        = "htf-cmp-public"
  description = "Cloud Mobility Platform (Streamlit) from the allowed IP"
  vpc_id      = aws_vpc.htf.id

  ingress {
    description = "Streamlit"
    from_port   = 8501
    to_port     = 8501
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
}

# ------------------------------------------------------------------ images and logs
resource "aws_ecr_repository" "htf" {
  for_each             = toset(["fog", "tms", "cmp", "edge"])
  name                 = "htf/${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true # terraform destroy also removes the images
}

resource "aws_cloudwatch_log_group" "htf" {
  name              = local.log_group
  retention_in_days = 7
}

# ------------------------------------------------------------------ permissions of the instances
resource "aws_iam_role" "node" {
  name = "htf-node"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ecr" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_role_policy" "logs" {
  name = "htf-logs"
  role = aws_iam_role.node.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = "${aws_cloudwatch_log_group.htf.arn}:*"
    }]
  })
}

# Results of the experiments: deploy/aws/collect-metrics.sh has the Fog nodes and the TMS copy
# their files here, then downloads them. terraform destroy also removes the bucket.
resource "aws_s3_bucket" "results" {
  bucket        = "htf-results-${data.aws_caller_identity.me.account_id}-${var.region}"
  force_destroy = true
}

resource "aws_iam_role_policy" "results" {
  name = "htf-results"
  role = aws_iam_role.node.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject"]
      Resource = "${aws_s3_bucket.results.arn}/*"
    }]
  })
}

resource "aws_iam_instance_profile" "node" {
  name = "htf-node"
  role = aws_iam_role.node.name
}

# ------------------------------------------------------------------ TMS database (RDS)
# PostgreSQL with PostGIS: the TMS creates the extension and the tables at startup
# (cloud/tms/src/main/resources/schema.sql). Not public, no backups, no final snapshot:
# terraform destroy deletes it with everything else.
resource "random_password" "db" {
  length  = 24
  special = false # RDS refuses / @ " and spaces; letters and digits are enough here
}

resource "aws_db_subnet_group" "htf" {
  name       = "htf"
  subnet_ids = [aws_subnet.htf.id, aws_subnet.db_b.id]
}

resource "aws_db_instance" "tms" {
  identifier             = "htf-tms"
  engine                 = "postgres"
  engine_version         = var.db_engine_version
  instance_class         = var.db_instance_class
  allocated_storage      = 20
  storage_type           = "gp3"
  db_name                = "htf"
  username               = "htf"
  password               = random_password.db.result
  db_subnet_group_name   = aws_db_subnet_group.htf.name
  vpc_security_group_ids = [aws_security_group.db.id]
  availability_zone      = data.aws_availability_zones.available.names[0] # same zone as the TMS
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true

  tags = { Name = "htf-tms-db" }
}

# ------------------------------------------------------------------ instances
locals {
  common = {
    region    = var.region
    registry  = local.registry
    log_group = local.log_group
    tag       = "latest"
  }
}

resource "aws_instance" "fog" {
  for_each               = local.fog_ip
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.fog_instance_type
  subnet_id              = aws_subnet.htf.id
  private_ip             = each.value
  vpc_security_group_ids = [aws_security_group.nodes.id]
  iam_instance_profile   = aws_iam_instance_profile.node.name

  metadata_options {
    http_tokens = "required"
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", merge(local.common, {
    name    = "fog-${each.key}"
    image   = "fog"
    restart = "unless-stopped"
    # The store-and-forward queue (visits not yet confirmed by the TMS) and the evaluation
    # measurements live on the instance, so they survive the container being removed by run.sh
    # (restore-fog.sh, redeploy.sh). deploy/aws/collect-metrics.sh copies the measurements to S3.
    volume = "-v /opt/htf/queue:/app/store-and-forward-queue -v /opt/htf/metrics:/app/metrics"
    env = {
      HTF_FOG_IDS        = each.key
      HTF_FOG_HOST       = each.value
      HTF_FOG_SEED       = local.seed_url
      HTF_NUMBER_OF_FOGS = var.number_of_fogs # the same on every node: it defines the area of each one
      HTF_TMS_URL        = local.tms_url
      HTF_HANDOVER       = var.handover_policy
      HTF_HOM_DB         = var.hom_db
      HTF_TTT_MIN        = var.ttt_min
      HTF_COVERAGE       = var.coverage
      HTF_FOG_LANES      = var.fog_lanes
      HTF_MQTT_QOS       = var.fog_mqtt_qos
    }
  }))
  user_data_replace_on_change = true

  tags       = { Name = "htf-fog-${each.key}", Role = "fog" }
  depends_on = [aws_route_table_association.htf, aws_cloudwatch_log_group.htf]
}

resource "aws_instance" "tms" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.cloud_instance_type
  subnet_id              = aws_subnet.htf.id
  private_ip             = local.tms_ip
  vpc_security_group_ids = [aws_security_group.nodes.id]
  iam_instance_profile   = aws_iam_instance_profile.node.name

  metadata_options {
    http_tokens = "required"
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", merge(local.common, {
    name    = "tms"
    image   = "tms"
    restart = "unless-stopped"
    volume  = ""
    # The TMS keeps trying to reach the database for up to 15 min (RDS may still be starting).
    env = {
      HTF_TMS_DB_URL      = "jdbc:postgresql://${aws_db_instance.tms.address}:${aws_db_instance.tms.port}/htf"
      HTF_TMS_DB_USER     = aws_db_instance.tms.username
      HTF_TMS_DB_PASSWORD = random_password.db.result
      HTF_TMS_DB_SSLMODE  = "require" # RDS for PostgreSQL 15+ only accepts TLS connections
      HTF_TMS_DB_WAIT_S   = 900
    }
  }))
  user_data_replace_on_change = true

  tags       = { Name = "htf-tms", Role = "tms" }
  depends_on = [aws_route_table_association.htf, aws_cloudwatch_log_group.htf]
}

resource "aws_instance" "cmp" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.cloud_instance_type
  subnet_id              = aws_subnet.htf.id
  private_ip             = local.cmp_ip
  vpc_security_group_ids = [aws_security_group.nodes.id, aws_security_group.cmp_public.id]
  iam_instance_profile   = aws_iam_instance_profile.node.name

  metadata_options {
    http_tokens = "required"
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", merge(local.common, {
    name    = "cmp"
    image   = "cmp"
    restart = "unless-stopped"
    volume  = ""
    env = {
      HTF_TMS_URL = local.tms_url
    }
  }))
  user_data_replace_on_change = true

  tags       = { Name = "htf-cmp", Role = "cmp" }
  depends_on = [aws_route_table_association.htf, aws_cloudwatch_log_group.htf]
}

resource "aws_instance" "edge" {
  for_each               = local.edge
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.edge_instance_type
  subnet_id              = aws_subnet.htf.id
  private_ip             = each.value.ip
  vpc_security_group_ids = [aws_security_group.nodes.id]
  iam_instance_profile   = aws_iam_instance_profile.node.name

  metadata_options {
    http_tokens = "required"
  }

  # One Edge process replays the day of every user of its group (HTF_USERS), each user with its
  # own handover state, over one shared MQTT connection per broker. It exits at the end of the day; run it again with deploy/aws/run-edges.sh
  user_data = templatefile("${path.module}/user_data.sh.tftpl", merge(local.common, {
    name    = "edge-${each.key}"
    image   = "edge"
    restart = "no"
    volume  = ""
    env = {
      HTF_USERS            = each.value.users
      HTF_DAY              = var.day
      HTF_ENTRY_BROKER     = "tcp://${local.fog_ip["1"]}:1901"
      # distributed entry: every Fog's broker is an entry point (phone uid mod N); empty = only Fog 1
      HTF_ENTRY_BROKERS = var.distributed_entry ? join(",", [for n in range(1, var.number_of_fogs + 1) :
      "tcp://${local.fog_ip[tostring(n)]}:${1900 + n}"]) : ""
      HTF_PING_INTERVAL_MS = var.ping_interval_ms
      HTF_START_DELAY_S    = local.edge_start_delay_s
      HTF_CONNECT_RETRY_S  = 900
    }
  }))
  user_data_replace_on_change = true

  tags       = { Name = "htf-edge-${each.key}", Role = "edge", Users = each.value.users }
  depends_on = [aws_route_table_association.htf, aws_cloudwatch_log_group.htf]
}

# ------------------------------------------------------------------ vCPU quota
# New accounts often have a low quota of On-Demand vCPUs (e.g. 32); every t4g instance uses 2.
# The plan stops here, before creating anything, when the environment would not fit.
data "aws_ec2_instance_type" "used" {
  for_each      = var.check_vcpu_quota ? toset([var.fog_instance_type, var.cloud_instance_type, var.edge_instance_type]) : toset([])
  instance_type = each.key
}

data "aws_servicequotas_service_quota" "vcpu" {
  count        = var.check_vcpu_quota ? 1 : 0
  service_code = "ec2"
  quota_code   = "L-1216C47A" # Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances
}

locals {
  vcpus_needed = var.check_vcpu_quota ? (
    var.number_of_fogs * data.aws_ec2_instance_type.used[var.fog_instance_type].default_vcpus +
    2 * data.aws_ec2_instance_type.used[var.cloud_instance_type].default_vcpus +
    length(var.edge_groups) * data.aws_ec2_instance_type.used[var.edge_instance_type].default_vcpus
  ) : 0
}

resource "terraform_data" "vcpu_quota" {
  count = var.check_vcpu_quota ? 1 : 0
  input = local.vcpus_needed

  lifecycle {
    precondition {
      condition     = local.vcpus_needed <= data.aws_servicequotas_service_quota.vcpu[0].value
      error_message = "The environment needs ${local.vcpus_needed} vCPUs, but the account quota for Running On-Demand Standard instances in ${var.region} is ${data.aws_servicequotas_service_quota.vcpu[0].value}. Ask for an increase in Service Quotas (quota L-1216C47A), use fewer Fog nodes or smaller types, or set check_vcpu_quota = false."
    }
  }
}
