set -e

STACK_NAME="WorkshopStack"
CLUSTER_NAME=unicorn-store

check_stack() {
    STATUS=$(aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" \
        --query "Stacks[0].StackStatus" \
        --output text 2>/dev/null)

    case $STATUS in
        CREATE_COMPLETE|UPDATE_COMPLETE)
            echo "Stack $STACK_NAME is ready."
            return 0
            ;;
        CREATE_IN_PROGRESS|UPDATE_IN_PROGRESS|UPDATE_COMPLETE_CLEANUP_IN_PROGRESS)
            echo "Stack $STACK_NAME is still in progress. Current status: $STATUS. Retrying in 10 seconds..."
            return 1
            ;;
        *)
            if [ -z "$STATUS" ]; then
                echo "Stack $STACK_NAME does not exist or an error occurred while checking. Retrying in 10 seconds..."
            else
                echo "Stack $STACK_NAME is in an unexpected state: $STATUS". Retrying in 10 seconds...
            fi
            return 1
            ;;
    esac
}

check_cluster() {
    cluster_status=$(aws eks describe-cluster --name "$CLUSTER_NAME" --query "cluster.status" --output text 2>/dev/null)
    if [ "$cluster_status" != "ACTIVE" ]; then
        echo "EKS cluster is not active. Current status: $cluster_status. Retrying in 10 seconds..."
        return 1
    fi
    echo "EKS cluster is active."
    return 0
}

while ! check_stack; do sleep 10; done

while ! check_cluster; do sleep 10; done

while ! aws eks --region $AWS_REGION update-kubeconfig --name $CLUSTER_NAME; do
    echo "Failed to update kubeconfig. Retrying in 10 seconds..."
    sleep 10
done

while ! kubectl get ns; do
    echo "Failed to get namespaces. Retrying in 10 seconds..."
    sleep 10
done

while ! kubectl get sa; do
    echo "Failed to get service accounts. Retrying in 10 seconds..."
    sleep 10
done
