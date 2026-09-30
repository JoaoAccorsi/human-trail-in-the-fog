# HTF on AWS (Terraform)

Deploys the prototype with **one EC2 instance per node**: 16 Fog nodes, the TMS, the
Cloud Mobility Platform and one Edge simulator per user (2 by default). Every instance
runs a single Docker container pulled from Amazon ECR.

```
                     your browser (allowed_cidr only)
                                 │ :8501
┌──────────────────────── VPC 10.20.0.0/16 · public subnet 10.20.1.0/24 ───────────────────────┐
│  htf-cmp 10.20.1.6 ──GET──► htf-tms 10.20.1.5 ◄──POST /capture── htf-fog-1..16 10.20.1.11-26 │
│                                                                   ▲  Chord ring + handover   │
│  htf-edge-4000 10.20.1.40, htf-edge-4005 10.20.1.41 ──MQTT :190N──┘  (HTTP :900N, P2P)       │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
        ECR (htf/fog, htf/tms, htf/cmp, htf/edge) · CloudWatch Logs (/htf) · SSM (no SSH)
```

| HTF component | AWS resource |
|---|---|
| Fog node N (MQTT broker + Chord + handover) | EC2 `t4g.small` `htf-fog-N`, private IP `10.20.1.(10+N)` |
| Trail Management Service | EC2 `t4g.small` `htf-tms`; JSONL files in `/opt/htf/data` on the instance |
| Cloud Mobility Platform | EC2 `t4g.small` `htf-cmp`, the only port open to the internet (8501, your IP only) |
| Edge simulator (one per user) | EC2 `t4g.micro` `htf-edge-<uid>` |
| Images / logs / access | ECR, CloudWatch Logs group `/htf`, Systems Manager (SSM) |

All instances are Graviton (ARM64) and run Amazon Linux 2023. They share one security group
that only accepts traffic from its own members.

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
`edge_start_delay_s` (120 s) more so the Chord ring can form first. At the end the script
prints the address of the Cloud Mobility Platform.

## During the experiment

| Command | What it does |
|---|---|
| `deploy/aws/logs.sh` / `deploy/aws/logs.sh fog-7` | Follows the logs of all nodes / of one node |
| `deploy/aws/run-edges.sh` | Replays the day again on every Edge |
| `deploy/aws/crash-fog.sh 7` | Abrupt failure of Fog 7 (kills its container, no warning) |
| `deploy/aws/restore-fog.sh 7` | Starts Fog 7 again; it rejoins the ring |
| `deploy/aws/redeploy.sh` | After changing the code: rebuilds, pushes and restarts every container |
| `deploy/aws/ssm.sh tag:Name htf-tms "<cmd>"` | Runs any shell command on the selected instances |

Handover parameters (`handover_policy`, `hom_db`, `ttt_min`), users and day are Terraform
variables: change `terraform.tfvars` and run `terraform apply` again (the affected instances
are recreated).

## Stop (important: the instances cost money while they exist)

```bash
deploy/aws/down.sh      # terraform destroy: removes instances, network, images and logs
```

## Cost (reference, us-east-1)

20 instances (18 × `t4g.small` at ~US$ 0.017/h, 2 × `t4g.micro` at ~US$ 0.008/h) cost about
**US$ 0.32 per hour**, plus US$ 0.005 per hour for each public IPv4 address (~US$ 0.10/h for 20)
and the 8 GB disks (~US$ 0.02/h): about **US$ 0.45 per hour** in total. A 10-hour experiment costs
about US$ 5; the same environment left on for a month would cost about US$ 320. New AWS accounts
receive free-tier credits that cover this. Check current prices with the AWS Pricing Calculator.

## Local test with the same images

`docker-compose.yml` in the repository root runs TMS, the 16 Fog nodes (in one container),
the platform and one Edge on your machine:

```bash
deploy/extract-users.sh 4005
docker compose up --build        # http://localhost:8501
```
