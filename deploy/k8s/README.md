# Kubernetes deployment

Kustomize base plus a `test` overlay: one replica, H2 database file on a PersistentVolumeClaim.

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

Later key for the same Secret: database password (PostgreSQL profile, M5).
Non-secret model settings (`AGENTICPILOT_MODEL_BASE_URL`, `AGENTICPILOT_MODEL_EU_ALLOWED`, model names) go into the ConfigMap.
Scaling beyond one replica needs PostgreSQL and sticky sessions or the Vaadin Kubernetes Kit (M5).
