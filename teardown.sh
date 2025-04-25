#!/bin/bash
set -e

echo "Starting teardown process..."

# Stop Minikube tunnel
pkill -f "minikube tunnel" || true

# Delete services and deployments
minikube kubectl -- delete svc,deploy user-deployment marketplace-deployment wallet-deployment --ignore-not-found=true

# Delete k8s resources with explicit path validation
echo "Deleting Kubernetes resources..."
[[ -d k8 ]] && (  # Check if directory exists
    minikube kubectl -- delete -f k8/h2-db-clusterip.yaml --ignore-not-found=true
    minikube kubectl -- delete -f k8/h2-db-deployment.yaml --ignore-not-found=true
    minikube kubectl -- delete -f k8/marketplace-hpa.yaml --ignore-not-found=true
) || echo "k8 directory not found - skipping resource deletion"

# Cleanup Docker images
minikube ssh "docker rmi -f user-service:v0.1 marketplace-service:v0.2 wallet-service:v0.3" 2>/dev/null || true

# Full Minikube reset
minikube delete --purge
