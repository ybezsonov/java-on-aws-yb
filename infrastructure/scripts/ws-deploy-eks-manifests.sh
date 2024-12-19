set -e

APP_NAME=${1:-"unicorn-store-spring"}

mkdir -p ~/environment/${APP_NAME}/k8s
cd ~/environment/${APP_NAME}/k8s

# echo Create manifests for the application
# cat <<EOF > ~/environment/${APP_NAME}/k8s/namespace.yaml
# apiVersion: v1
# kind: Namespace
# metadata:
#   name: ${APP_NAME}
# EOF

# cat <<EOF > ~/environment/${APP_NAME}/k8s/service-account.yaml
# apiVersion: v1
# kind: ServiceAccount
# metadata:
#   name: ${APP_NAME}
#   namespace: ${APP_NAME}
#   annotations:
#     eks.amazonaws.com/role-arn: arn:aws:iam::${ACCOUNT_ID}:role/unicorn-store-eks-pod-role
# EOF

ECR_URI=$(aws ecr describe-repositories --repository-names $APP_NAME \
  | jq --raw-output '.repositories[0].repositoryUri')
SPRING_DATASOURCE_URL=$(aws ssm get-parameter --name databaseJDBCConnectionString \
  | jq --raw-output '.Parameter.Value')

cat <<EOF > ~/environment/${APP_NAME}/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $APP_NAME
  # namespace: $APP_NAME
  labels:
    app: $APP_NAME
spec:
  replicas: 1
  selector:
    matchLabels:
      app: $APP_NAME
  template:
    metadata:
      labels:
        app: $APP_NAME
    spec:
      # serviceAccountName: $APP_NAME
      containers:
        - name: $APP_NAME
          resources:
            requests:
              cpu: "1"
              memory: "2Gi"
          image: ${ECR_URI}:latest
          imagePullPolicy: Always
          env:
            - name: AWS_REGION
              value: ${AWS_REGION}
            - name: SPRING_DATASOURCE_URL
              value: ${SPRING_DATASOURCE_URL}
          ports:
            - containerPort: 8080
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            failureThreshold: 6
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            failureThreshold: 6
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            failureThreshold: 6
            periodSeconds: 10
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]
          securityContext:
            runAsNonRoot: true
            allowPrivilegeEscalation: false
EOF

# aws eks create-pod-identity-association --cluster-name $CLUSTER_NAME \
#   --namespace $APP_NAME --service-account $APP_NAME \
#   --role-arn arn:aws:iam::$ACCOUNT_ID:role/unicorn-store-eks-pod-role

cat <<EOF > ~/environment/$APP_NAME/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: $APP_NAME
  # namespace: $APP_NAME
  labels:
    app: $APP_NAME
spec:
  type: LoadBalancer
  ports:
    - port: 80
      targetPort: 8080
      protocol: TCP
  selector:
    app: $APP_NAME
EOF

cat <<EOF > ~/environment/$APP_NAME/k8s/ingress-class.yaml
apiVersion: networking.k8s.io/v1
kind: IngressClass
metadata:
  # namespace: $APP_NAME
  labels:
    app.kubernetes.io/name: LoadBalancerController
  name: alb
spec:
  controller: eks.amazonaws.com/alb
EOF

cat <<EOF > ~/environment/$APP_NAME/k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  # namespace: $APP_NAME
  name: $APP_NAME
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
spec:
  ingressClassName: alb
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: $APP_NAME
                port:
                  number: 80
EOF

git -C ~/environment/$APP_NAME add .
git -C ~/environment/$APP_NAME commit -m "add k8s manifests"

echo Deploy the manifest to the EKS cluster
kubectl apply -f ~/environment/$APP_NAME/k8s/

echo Verify that the application is running properly
kubectl wait deployment $APP_NAME --for condition=Available=True --timeout=120s
kubectl get deploy
SVC_URL=http://$(kubectl get ingress $APP_NAME -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
while [[ $(curl -s -o /dev/null -w "%{http_code}" $SVC_URL/) != "200" ]]; do echo "Service not yet available ..." &&  sleep 5; done
echo $SVC_URL
curl --location $SVC_URL; echo
curl --location --request POST $SVC_URL'/unicorns' --header 'Content-Type: application/json' --data-raw '{
    "name": "'"Something-$(date +%s)"'",
    "age": "20",
    "type": "Animal",
    "size": "Very big"
}' | jq
