set -e

CLUSTER_NAME=${1:-"unicorn-store"}

check_cluster() {
    cluster_status=$(aws eks describe-cluster --name "$CLUSTER_NAME" --query "cluster.status" --output text 2>/dev/null)
    if [ "$cluster_status" != "ACTIVE" ]; then
        echo "EKS cluster is not active. Current status: $cluster_status. Retrying in 10 seconds..."
        return 1
    fi
    echo "EKS cluster is active."
    return 0
}

while ! check_cluster; do sleep 10; done

while ! aws eks --region $AWS_REGION update-kubeconfig --name $CLUSTER_NAME; do
    echo "Failed to update kubeconfig. Retrying in 10 seconds..."
    sleep 10
done

while ! kubectl get ns; do
    echo "Failed to get namespaces. Retrying in 10 seconds..."
    sleep 10
done
