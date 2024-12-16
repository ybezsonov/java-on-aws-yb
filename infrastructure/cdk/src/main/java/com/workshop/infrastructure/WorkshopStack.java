package com.workshop.infrastructure;

import com.workshop.infrastructure.constructs.VSCodeIde;
import com.workshop.infrastructure.constructs.CustomVpc;
import com.workshop.infrastructure.constructs.EKSCluster;
import com.workshop.infrastructure.constructs.WorkshopInfrastructure;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.DefaultStackSynthesizerProps;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.eks.CfnAccessEntry;
import software.amazon.awscdk.services.eks.CfnAccessEntry.AccessScopeProperty;
import software.amazon.awscdk.services.eks.CfnAccessEntry.AccessPolicyProperty;

import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

public class WorkshopStack extends Stack {

    public WorkshopStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public WorkshopStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, StackProps.builder()
            .synthesizer(new DefaultStackSynthesizer(DefaultStackSynthesizerProps.builder()
                .generateBootstrapVersionRule(false)  // This disables the bootstrap version parameter
                .build()))
            .build());       

        var workshopVpc = new CustomVpc(this, "UnicornVpc");
        var vpc = workshopVpc.getVpc();

        var workshopInfrastructure = new WorkshopInfrastructure(this, id, vpc);
        var eksClusterName = "unicorn-store";
        var workshopEKSCluster = new EKSCluster(this, eksClusterName, vpc);
        var accountId = this.getAccount();

        var ideRole = Role.Builder.create(this, "IdeRole")
            .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AdministratorAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("ReadOnlyAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore")
            ))
            .build();

        // Add access to the EKS cluster            
        CfnAccessEntry.Builder.create(this, "IdeRoleEKSAccessEntry")
            .clusterName(eksClusterName)
            .principalArn(ideRole.getRoleArn())
            .accessPolicies(List.of(AccessPolicyProperty.builder()
                    .accessScope(AccessScopeProperty.builder()
                        .type("cluster")
                        .build())
                    .policyArn("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy")
                    .build()))
            .build();

        CfnAccessEntry.Builder.create(this, "ParticipantRoleEKSAccessEntry")
            .clusterName(eksClusterName)
            .principalArn("arn:aws:iam::" + accountId + ":role/WSParticipantRole")
            .accessPolicies(List.of(AccessPolicyProperty.builder()
                        .accessScope(AccessScopeProperty.builder()
                        .type("cluster")
                        .build())
                    .policyArn("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy")
                    .build()))
            .build();            

        workshopInfrastructure.getEventBridge().grantPutEventsTo(ideRole);
        workshopInfrastructure.getDatabaseSecret().grantRead(ideRole);
        workshopInfrastructure.getParamJdbc().grantRead(ideRole);

        var ideProps = new VSCodeIde.VSCodeIdeProps();
        ideProps.setRole(ideRole);
        ideProps.setVpc(vpc);
        ideProps.setAvailabilityZone(vpc.getAvailabilityZones().get(0));
        // Create a security group to access an application at the port 8080
        SecurityGroup ideSecurityGroup = SecurityGroup.Builder.create(this, "AppSecurityGroup")
            .vpc(vpc)
            .allowAllOutbound(true)
            .description("App security group")
            .build();
        ideSecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.tcp(8080),
            "HTTP to an application"
        );
        ideProps.setAdditionalSecurityGroups(List.of(ideSecurityGroup));
        ideProps.setBootstrapTimeoutMinutes(30);
        ideProps.setTerminalOnStartup(true);
        ideProps.setExtensions(Arrays.asList(
            // "amazonwebservices.aws-toolkit-vscode",
            // "amazonwebservices.amazon-q-vscode",
            "ms-azuretools.vscode-docker",
            "ms-kubernetes-tools.vscode-kubernetes-tools",
            "vscjava.vscode-java-pack"
        ));
        String bootstrapScript = """
            date

            echo '=== Clone Git repository ==='
            sudo -H -u ec2-user bash -c "git clone https://github.com/ybezsonov/java-on-aws-yb ~/java-on-aws/"
            sudo -H -u ec2-user bash -c "cd ~/java-on-aws && git checkout refactor"

            echo '=== Setup IDE ==='
            sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/setup-ide.sh"

            echo '=== Setup App ==='
            sudo -H -i -u ec2-user bash -c "~/java-on-aws/infrastructure/scripts/setup-app.sh"
            """;
        ideProps.setBootstrapScript(bootstrapScript);

        var vsCodeIde = new VSCodeIde(this, "VSCodeIde", ideProps);

        CfnOutput.Builder.create(this, "IdeUrl")
            .value(vsCodeIde.getIdeUrl())
            .description("Workshop IDE Url")
            .build();
        CfnOutput.Builder.create(this, "IdePassword")
            .value(vsCodeIde.getIdePassword())
            .description("Workshop IDE Password")
            .build();
        CfnOutput.Builder.create(this, "KubeconfigCommand")
            .value(String.format("aws eks --region %s update-kubeconfig --name %s",
                Stack.of(this).getRegion(), eksClusterName))
            .description("Command to update kubeconfig")
            .build();  
    }
}
