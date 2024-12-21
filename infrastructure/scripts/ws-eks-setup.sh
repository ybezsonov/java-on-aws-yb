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

cat <<EOF | kubectl create -f -
apiVersion: networking.k8s.io/v1
kind: IngressClass
metadata:
  labels:
    app.kubernetes.io/name: LoadBalancerController
  name: alb
spec:
  controller: eks.amazonaws.com/alb
EOF

cat <<EOF | kubectl create -f -
apiVersion: karpenter.sh/v1
kind: NodePool
metadata:
  name: dedicated
spec:
  weight: 50
  template:
    spec:
      nodeClassRef:
        group: eks.amazonaws.com
        kind: NodeClass
        name: default
      requirements:
        - key: karpenter.sh/capacity-type
          operator: In
          values: ["on-demand"]
        - key: node.kubernetes.io/instance-type
          operator: In
          values: ["c5.xlarge"]
  limits:
    cpu: 20
EOF

# echo "Deploying secrets-store-csi-driver ..."
# helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
# helm install -n kube-system csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver --set syncSecret.enabled=true
# kubectl apply -f https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml

echo "EKS cluster setup complete."
