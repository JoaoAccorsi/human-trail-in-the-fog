# HTF on AWS (Terraform)

Deploys the prototype with **one EC2 instance per node**: `number_of_fogs` Fog nodes (16 by
default), the TMS, the Cloud Mobility Platform and one Edge simulator per element of
`edge_groups` (one by default, with users 4000 and 4005). Each Edge replays the day of every
user of its group. Every instance runs a single Docker container pulled from Amazon ECR.

![HTF on AWS](../../docs/img/aws-deployment.png)

| HTF component | AWS resource |
|---|---|
| Fog node N (MQTT broker + Chord + handover) | EC2 `t4g.small` `htf-fog-N`, private IP `10.20.1.N` (from N = 256 on: `10.20.2.x`, ...); store-and-forward queue in `/opt/htf/queue` on the instance |
| Trail Management Service | EC2 `t4g.small` `htf-tms`, `10.20.0.5`; JSONL files in `/opt/htf/data` on the instance |
| Cloud Mobility Platform | EC2 `t4g.small` `htf-cmp`, `10.20.0.6`, the only port open to the internet (8501, your IP only) |
| Edge simulator (one per group of users) | EC2 `t4g.small` `htf-edge-1`, `htf-edge-2`, ... at `10.20.0.(9+i)`; tag `Users` lists its users |
| Images / logs / access | ECR, CloudWatch Logs group `/htf`, Systems Manager (SSM) |

All instances are Graviton (ARM64), run Amazon Linux 2023 and sit in one `/20` subnet. They
share one security group that only accepts traffic from its own members.

## Size of the experiment

| Variable | Default | What it sets |
|---|---|---|
| `number_of_fogs` | `16` | Fog nodes, one instance each. It must be n·n with 200 % n == 0 (1, 4, 16, 25, 64, 100, 400, 625): every node receives `HTF_NUMBER_OF_FOGS` and covers a square of 200/n cells (50 with 16 nodes, 20 with 100) |
| `edge_groups` | `["4000,4005"]` | One Edge instance per element, in `HTF_USERS` form: `["4000-4999", "5000-5999"]` runs 2000 users on 2 instances. A `t4g.small` handles about 1000 users |
| `edge_start_delay_s` | `null` | Wait before the Edges start, so the ring converges: by default 120 s + 2 s per Fog node (152 s with 16, 320 s with 100) |
| `check_vcpu_quota` | `true` | Stops `terraform apply` before creating anything when the instances need more vCPUs than the account quota |

**vCPU quota.** Every `t4g` instance uses 2 vCPUs: the default environment (16 + 2 + 1 instances)
needs 38, and 100 Fog nodes need more than 200. New accounts often have a quota of only 32 vCPUs
for *Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances*. The plan checks it and,
if it does not fit, says how many vCPUs are needed; ask for an increase in the console
(Service Quotas → Amazon EC2 → quota `L-1216C47A`; it may take a few hours) or reduce the size.

## Prerequisites (once)

- An AWS account and the AWS CLI configured (`aws configure`, or `aws sso login`).
- Terraform ≥ 1.5 and Docker Desktop (with `buildx`): `brew install terraform awscli`.
- The YJMob100K dataset 2 in `YJMob100K/yjmob100k-dataset2.csv` (only the rows of the
  simulated users go into the Edge image).

## Start

```bash
cp deploy/aws/terraform.tfvars.example deploy/aws/terraform.tfvars
# edit allowed_cidr: your public IP (curl https://checkip.amazonaws.com) + /32
deploy/aws/up.sh
```

`up.sh` runs `terraform apply` (it asks for confirmation), extracts the users' rows from the
dataset, builds the four images for ARM64 and pushes them to ECR. The instances keep trying to
pull their image, so they start as soon as the push finishes (a few minutes). The Edges wait
`edge_start_delay_s` more (152 s with 16 Fog nodes) so the Chord ring can form first. At the end the script
prints the address of the Cloud Mobility Platform.

## During the experiment

| Command | What it does |
|---|---|
| `deploy/aws/logs.sh` / `deploy/aws/logs.sh fog-7` | Follows the logs of all nodes / of one node |
| `deploy/aws/run-edges.sh` | Replays the day again on every Edge |
| `deploy/aws/crash-fog.sh 7` | Abrupt failure of Fog 7 (kills its container, no warning) |
| `deploy/aws/restore-fog.sh 7` | Starts Fog 7 again; it rejoins the ring and sends the visits left in its store-and-forward queue (`/opt/htf/queue` survives the container) |
| `deploy/aws/redeploy.sh` | After changing the code: rebuilds, pushes and restarts every container |
| `deploy/aws/ssm.sh tag:Name htf-tms "<cmd>"` | Runs any shell command on the selected instances |

Handover parameters (`handover_policy`, `hom_db`, `ttt_min`), number of Fog nodes, users and day
are Terraform variables: change `terraform.tfvars` and run `terraform apply` again (the affected instances
are recreated).

## Stop (important: the instances cost money while they exist)

```bash
deploy/aws/down.sh      # terraform destroy: removes instances, network, images and logs
```

## Cost (reference, us-east-1)

The default environment has 19 `t4g.small` instances (16 Fog nodes, TMS, platform and one Edge) at
~US$ 0.017/h each: about **US$ 0.32 per hour**, plus US$ 0.005 per hour for each public IPv4
address (~US$ 0.10/h for 19) and the 8 GB disks (~US$ 0.02/h): about **US$ 0.44 per hour** in total.
A 10-hour experiment costs about US$ 4.40; the same environment left on for a month would cost
about US$ 320. With 100 Fog nodes (103 instances) it is about **US$ 2.35 per hour**. New AWS accounts
receive free-tier credits that cover this. Check current prices with the AWS Pricing Calculator.

## Local test with the same images

`docker-compose.yml` in the repository root runs TMS, the 16 Fog nodes (in one container),
the platform and one Edge on your machine:

```bash
deploy/extract-users.sh 4005
docker compose up --build        # http://localhost:8501
```
