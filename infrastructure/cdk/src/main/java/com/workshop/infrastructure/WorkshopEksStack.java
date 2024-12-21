package com.workshop.infrastructure;

import com.workshop.infrastructure.unicorn.CoreInfrastructure;
import com.workshop.infrastructure.constructs.VSCodeIde;
import com.workshop.infrastructure.constructs.WorkshopVpc;
import com.workshop.infrastructure.constructs.EKSCluster;
import com.workshop.infrastructure.constructs.VSCodeIdeProps;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.DefaultStackSynthesizerProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.eks.CfnAccessEntry;
import software.amazon.awscdk.services.eks.CfnAccessEntry.AccessScopeProperty;
import software.amazon.awscdk.services.eks.CfnAccessEntry.AccessPolicyProperty;
import software.amazon.awscdk.services.eks.CfnPodIdentityAssociation;
import software.amazon.awscdk.services.apprunner.alpha.VpcConnector;
// import software.amazon.awscdk.services.eks.ICluster;
// import software.amazon.awscdk.services.eks.Cluster;
// import software.amazon.awscdk.services.eks.ClusterAttributes;
// import software.amazon.awscdk.services.eks.ServiceAccountOptions;
// import software.amazon.awscdk.cdk.lambdalayer.kubectl.v31.KubectlV31Layer;

import software.constructs.Construct;
import java.util.Arrays;
import java.util.List;

public class WorkshopEksStack extends Stack {
    private String bootstrapScript = """
        date

        echo '=== Clone Git repository ==='
        sudo -H -u ec2-user bash -c "git clone https://github.com/ybezsonov/java-on-aws-yb ~/java-on-aws/"
        sudo -H -u ec2-user bash -c "cd ~/java-on-aws && git checkout refactor"

        echo '=== Setup IDE ==='
        sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/setup-ide.sh"

        echo '=== Additional Setup ==='
        # sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/setup-app.sh"
        # sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/ws-containerize.sh"
        sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/ws-eks-setup.sh"
        # sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/ws-eks-deploy-app.sh"
        # sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/ws-eks-cleanup-app.sh"
        """;

    public WorkshopEksStack(final Construct scope, final String id) {
        super(scope, id, StackProps.builder()
            .synthesizer(new DefaultStackSynthesizer(DefaultStackSynthesizerProps.builder()
                .generateBootstrapVersionRule(false)  // This disables the bootstrap version parameter
                .build()))
            .build());

        var accountId = Stack.of(this).getAccount();
        // var region = Stack.of(this).getRegion();

        var workshopVpc = new WorkshopVpc(this, "WorkshopVpc");
        var vpc = workshopVpc.getVpc();

        var ideRole = Role.Builder.create(this, "IdeRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .roleName("workshop-ide-user")
                .build();

        var ideProps = new VSCodeIdeProps();
        ideProps.setBootstrapScript(bootstrapScript);
        ideProps.setVpc(vpc);
        ideProps.setRole(ideRole);
        ideProps.setEnableAppSecurityGroup(true);
        ideProps.setInstanceType(InstanceType.of(InstanceClass.M5, InstanceSize.XLARGE));
        ideProps.setExtensions(Arrays.asList(
            // "amazonwebservices.aws-toolkit-vscode",
            // "amazonwebservices.amazon-q-vscode",
            "ms-azuretools.vscode-docker",
            "ms-kubernetes-tools.vscode-kubernetes-tools",
            "vscjava.vscode-java-pack"
        ));

        // Create Internal security group for application with access from IDE to EKS cluster
        var unicornSecurityGroup = SecurityGroup.Builder.create(this, "UnicornSecurityGroup")
            .vpc(workshopVpc.getVpc())
            .allowAllOutbound(false)
            .securityGroupName("Unicorn security group")
            .description("Unicorn security group")
            .build();

        // Add ingress rule to allow all traffic from within the same security group
        unicornSecurityGroup.getConnections().allowInternally(
            Port.allTraffic(),
            "Allow all internal traffic"
        );
        ideProps.setAdditionalSecurityGroups(List.of(unicornSecurityGroup));

        new VSCodeIde(this, "VSCodeIde", ideProps);

        var workshopCoreInfrastructure = new CoreInfrastructure(this, id, vpc);
        workshopCoreInfrastructure.getEventBridge().grantPutEventsTo(ideRole);
        workshopCoreInfrastructure.getParamJdbc().grantRead(ideRole);
        workshopCoreInfrastructure.getDatabaseSecret().grantRead(ideRole);
        workshopCoreInfrastructure.getEcrRepository().grantPullPush(ideRole);

        var eksClusterName = "unicorn-store";
        var workshopEKSCluster = new EKSCluster(this, eksClusterName, vpc, unicornSecurityGroup);

        // Add access to the EKS cluster
        var ideRoleEKSAccessEntry = CfnAccessEntry.Builder.create(this, "IdeRoleEKSAccessEntry")
            .clusterName(eksClusterName)
            .principalArn(ideRole.getRoleArn())
            .accessPolicies(List.of(AccessPolicyProperty.builder()
                    .accessScope(AccessScopeProperty.builder()
                        .type("cluster")
                        .build())
                    .policyArn("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy")
                    .build()))
            .build();
        ideRoleEKSAccessEntry.getNode().addDependency(workshopEKSCluster);

        var participantRoleEKSAccessEntry = CfnAccessEntry.Builder.create(this, "ParticipantRoleEKSAccessEntry")
            .clusterName(eksClusterName)
            .principalArn("arn:aws:iam::" + accountId + ":role/WSParticipantRole")
            .accessPolicies(List.of(AccessPolicyProperty.builder()
                        .accessScope(AccessScopeProperty.builder()
                        .type("cluster")
                        .build())
                    .policyArn("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy")
                    .build()))
            .build();
        participantRoleEKSAccessEntry.getNode().addDependency(workshopEKSCluster);

        var podIdentityAssociation = CfnPodIdentityAssociation.Builder.create(this, "CfnPodIdentityAssociationDefault")
            .clusterName(eksClusterName)
            .namespace("default")
            .roleArn("arn:aws:iam::" + Stack.of(this).getAccount() + ":role/unicornstore-eks-pod-role")
            .serviceAccount("default")
            .build();
        podIdentityAssociation.getNode().addDependency(workshopEKSCluster);

        VpcConnector.Builder.create(this, "UnicornStoreVpcConnector")
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build())
            .vpcConnectorName("unicornstore-vpc-connector")
            .build();

        // ICluster importedCluster = Cluster.fromClusterAttributes(this, "Eks", ClusterAttributes.builder()
        //     .openIdConnectProvider(workshopEKSCluster.getProvider())
        //     .clusterName("unicorn-store")
        //     .kubectlRoleArn(ideRole.getRoleArn())
        //     .kubectlLayer(new KubectlV31Layer(this, "UnicornStoreClusterKubectlLayer"))
        //     .build());
        // importedCluster.getNode().addDependency(workshopEKSCluster);

        // var appServiceAccount =
        //     importedCluster.addServiceAccount("UnicornStoreServiceAccount",
        //         ServiceAccountOptions.builder().name("unicorn-store").namespace("default").build());
        // workshopCoreInfrastructure.getDatabaseSecret().grantRead(appServiceAccount);
    }
}
