# Local Kubernetes test

For trying the Kubernetes deployment on your own machine. It uses the H2 test setup (one replica, data on a volume of the cluster), not the production overlay.

1. Start Docker Desktop and turn on **Settings > Kubernetes > Enable** (or install [kind](https://kind.sigs.k8s.io), the script then creates its own cluster).
2. From the repository root:

   ```sh
   AGENTICPILOT_SEED_PASSWORD='choose-a-password' deploy/local/setup.sh
   # optional, for real model answers: AGENTICPILOT_MODEL_API_KEY='...'
   ```

   The script builds the image locally (`agenticpilot:local`), deploys `deploy/k8s/overlays/local` and waits until the pod is ready.
3. Forward the port and open <http://localhost:8080>:

   ```sh
   kubectl -n agenticpilot port-forward svc/agenticpilot 8080:8080
   ```

Run the script again after code changes to rebuild and redeploy. To start fresh, delete the namespace (`kubectl delete namespace agenticpilot`).
