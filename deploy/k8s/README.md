# Kubernetes deployment

Kustomize base plus two overlays:

| Overlay | Database | Replicas | Use |
|---|---|---|---|
| `overlays/test` | H2 file on a PersistentVolumeClaim | 1 (`Recreate`) | Try it out, demos |
| `overlays/production` | PostgreSQL | 2 to 5 (`RollingUpdate`, no downtime) | Real use |

`components/postgres-example` is an optional single PostgreSQL instance for clusters without a managed database.
The full operating guide is [docs/RUNBOOK.md](../../docs/RUNBOOK.md).

## Test overlay

```sh
kubectl apply -f - <<EOF
apiVersion: v1
kind: Namespace
metadata: {name: agenticpilot}
EOF
# Secrets never live in the repository. Create them out of band:
kubectl -n agenticpilot create secret generic agenticpilot-secrets \
  --from-literal=AGENTICPILOT_SEED_PASSWORD='<choose one>' \
  --from-literal=AGENTICPILOT_MODEL_API_KEY='<Claude API key>'
kubectl apply -k deploy/k8s/overlays/test
```

## Production overlay

```sh
kubectl -n agenticpilot create secret generic agenticpilot-secrets \
  --from-literal=AGENTICPILOT_SEED_PASSWORD='<first sign-in password, change it after the first sign-in>' \
  --from-literal=AGENTICPILOT_MODEL_API_KEY='<Claude API key>' \
  --from-literal=AGENTICPILOT_DB_USER='agenticpilot' \
  --from-literal=AGENTICPILOT_DB_PASSWORD='<database password>'
# Edit the host in base/ingress.yaml, the image tag and AGENTICPILOT_DB_URL in overlays/production/configmap-patch.yaml, then:
kubectl apply -k deploy/k8s/overlays/production
```

What the overlay does:

- **Two replicas, rolling updates** with `maxUnavailable: 0`, a 10 s `preStop` pause and a 60 s grace period so running requests and streams finish.
- **Users stay signed in** when a replica goes away: the sign-in is also stored in PostgreSQL (see the runbook). The ingress uses a sticky cookie (`AP_ROUTE`) so a user normally stays on one replica; open dialogs on a replica that is lost are lost, the sign-in is not.
- **Probes and metrics on port 8081**, which the Service and the Ingress do not publish. Prometheus scrapes `/actuator/prometheus` there.
- **PodDisruptionBudget** (at least one pod), **HorizontalPodAutoscaler** (CPU 70 %), pods spread over nodes, read-only root file system.
- **NetworkPolicy**: in only from the ingress controller (8080) and the `monitoring` namespace (8081); out only to DNS, PostgreSQL and HTTPS on the internet, never to internal address ranges. Needs a CNI that enforces NetworkPolicy. For a managed database outside the cluster, add its address range to the policy.

Non-secret model settings (`AGENTICPILOT_MODEL_BASE_URL`, `AGENTICPILOT_MODEL_EU_ALLOWED`, model names) go into the ConfigMap.
Check the rendered output before applying: `kubectl kustomize deploy/k8s/overlays/production`.
