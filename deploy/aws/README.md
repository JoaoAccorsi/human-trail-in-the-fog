# HTF on AWS (Terraform)

Deploys the prototype with **one EC2 instance per node**: `number_of_fogs` Fog nodes, the TMS, the
Cloud Mobility Platform and one Edge simulator per element of `edge_groups`. Every instance runs a
single Docker container pulled from Amazon ECR; the TMS database is Amazon RDS for PostgreSQL with
PostGIS.

![HTF on AWS](../../docs/img/aws-deployment.png)

## Environment used in the paper

`terraform.tfvars`: region `us-east-2`, day 20, 16 Fog nodes, 13 Edge groups (25,000 users).

| HTF component | AWS resource |
|---|---|
| Fog node N (MQTT broker, Chord, handover, EPCIS queue) | EC2 `t4g.medium` `htf-fog-N`, `10.20.1.N`; store-and-forward queue in `/opt/htf/queue` |
| Trail Management Service | EC2 `t4g.small` `htf-tms`, `10.20.0.5` |
| TMS database | RDS PostgreSQL 16 + PostGIS `db.t4g.small`, same zone, not public |
| Cloud Mobility Platform | EC2 `t4g.small` `htf-cmp`, `10.20.0.6`; the only port open to the internet (8501, from `allowed_cidr`, with login) |
| Edge simulators | 13 × EC2 `t4g.large` `htf-edge-i` (`10.20.0.(9+i)`), up to 2,000 users each |
| Images / logs / commands / results | ECR, CloudWatch Logs `/htf`, Systems Manager, S3 `htf-results-<account>-<region>` |

All instances are Graviton (ARM64) with Amazon Linux 2023, in one VPC, one public subnet and one
Availability Zone, and share a security group that only admits traffic among them. A second,
private subnet (`10.20.16.0/24`, another zone) only completes the RDS subnet group. Other settings of
the paper: one ping per 30-minute slot every 10 s (`ping_interval_ms = 10000`), 32 lanes per Fog node
(`fog_lanes`), MQTT QoS 1 and distributed entry nodes (`distributed_entry = true`).

| Variable | Repository default | What it sets |
|---|---|---|
| `number_of_fogs` | `16` | Fog nodes; must be n·n with 200 % n == 0 (each covers 200/n × 200/n cells) |
| `edge_groups` | `["4000,4005"]` | One Edge instance per element, in `HTF_USERS` form (e.g. `"0-1999"`) |
| `fog_instance_type` / `edge_instance_type` / `db_instance_class` | `t4g.small` / `t4g.small` / `db.t4g.micro` | Instance sizes (the paper used `t4g.medium` / `t4g.large` / `db.t4g.small`) |
| `handover_policy`, `hom_db`, `ttt_min`, `coverage` | `rsrp`, `3`, `60`, `on` | Handover policy and parameters; coverage of a failed Fog node's area |
| `check_vcpu_quota` | `true` | Stops `terraform apply` if the instances need more vCPUs than the account quota (the paper's environment needs 62; ask for an increase of quota `L-1216C47A` in Service Quotas) |

## Prerequisites (once)

- An AWS account with the AWS CLI configured (`aws configure` or `aws sso login`).
- Terraform ≥ 1.5 and Docker Desktop with `buildx` (`brew install terraform awscli`).
- The YJMob100K dataset 2 in `YJMob100K/yjmob100k-dataset2.csv`.

## Start

```bash
cp deploy/aws/terraform.tfvars.example deploy/aws/terraform.tfvars   # set allowed_cidr and the size
deploy/aws/up.sh
```

`up.sh` runs `terraform apply`, extracts the users' rows from the dataset, builds the four ARM64
images and pushes them to ECR, and prints the address of the platform. RDS takes 5 to 10 minutes to
create; the Edges wait 120 s + 2 s per Fog node so the Chord ring forms first. Before measuring,
wait for `[TMS] listening` in `deploy/aws/logs.sh tms`.

## Running the scenarios

Each run replays the day on every Edge; the event happens at 12:00 of the replayed day (slot 24).

| Scenario | Command |
|---|---|
| S1: area vs. HTF | `CLEAN=1 deploy/aws/run-edges.sh` (with `handover_policy = "area"` for the baseline) |
| S2: TMS down for 150 s | `CLEAN=1 OUTAGE_S=150 deploy/aws/run-edges.sh` |
| S3: crash of Fog 7 | `CLEAN=1 CRASH_FOG=7 deploy/aws/run-edges.sh` (with `coverage = "off"` for the baseline), then `deploy/aws/restore-fog.sh 7` |
| S4: 1k, 5k, 25k users | `CLEAN=1 deploy/aws/run-edges.sh` with the corresponding `edge_groups` |

After a run, `deploy/aws/wait-edges.sh` waits for the end of the day and
`deploy/aws/collect-metrics.sh <label>` gathers the Fog measurements, visits and EPCIS documents
(through the S3 bucket) into `results/aws/<label>/` and appends the metrics to `results/metrics.csv`.

Other commands: `deploy/aws/logs.sh [fog-7]` follows the logs, `deploy/aws/redeploy.sh [fog tms ...]`
rebuilds and restarts containers after a code change, and `deploy/aws/ssm.sh tag:Name htf-tms "<cmd>"`
runs a command on instances. Changing a Terraform variable needs `terraform apply` again.

## Stop (the instances cost money while they exist)

```bash
deploy/aws/down.sh      # terraform destroy: removes instances, database, network, images and logs
```
