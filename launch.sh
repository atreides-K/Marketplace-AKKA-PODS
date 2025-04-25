#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

echo "Starting Minikube..."
minikube start

# --- DO NOT configure Docker to use Minikube's daemon ---
# eval $(minikube docker-env) # <<< REMOVE OR COMMENT OUT THIS LINE

echo "Ensuring host docker environment is active (undo minikube docker-env if set previously)"
eval $(minikube docker-env -u) # <<< ADD THIS LINE (optional but safe)

# Build Docker images for each service using HOST Docker
echo "Building user-service image..."
cd user-service
# Use buildx explicitly or just build, relies on host Docker having buildx/BuildKit
docker buildx build -t user-service:v0.1 . --load # Use --load to make image available locally
# OR: docker build -t user-service:v0.1 .
cd ..

echo "Building wallet-service image..."
cd wallet-service
docker buildx build -t wallet-service:v0.3 . --load # Use --load
# OR: docker build -t wallet-service:v0.3 .
cd ..

# Load the images built on the host into Minikube's Docker daemon
echo "Loading images into Minikube..."
minikube image load user-service:v0.1
minikube image load wallet-service:v0.3

# Create deployments from the images now inside Minikube
echo "Creating deployments..."
minikube kubectl -- create deployment user-deployment --image=user-service:v0.1
minikube kubectl -- create deployment wallet-deployment --image=wallet-service:v0.3

# ... (rest of your script remains the same) ...

sleep 10

echo "Exposing user-service on port 8080..."
minikube kubectl -- expose deployment user-deployment --type=LoadBalancer --port=8080

echo "Exposing wallet-service on port 8082 (mapping container port 8080)..."
minikube kubectl -- expose deployment wallet-deployment --type=LoadBalancer --port=8082 --target-port=8080

echo "Starting minikube tunnel..."
minikube tunnel &

# echo "Applying additional Kubernetes YAML configurations..."
# minikube kubectl -- apply -f k8s/h2-db-deployment.yaml
# minikube kubectl -- apply -f k8s/h2-db-clusterip.yaml

echo "Setup complete. Check the status of your deployments and services:"
minikube kubectl -- get deployments
minikube kubectl -- get svc

echo "Remember to kill the 'minikube tunnel' process when done (e.g., using 'pkill minikube' or 'fg' then Ctrl+C)"