package com.workshop.infrastructure;

import com.workshop.infrastructure.constructs.VSCodeIde;
import com.workshop.infrastructure.constructs.WorkshopVpc;
import com.workshop.infrastructure.constructs.EKSCluster;
import com.workshop.infrastructure.constructs.UnicornStoreInfrastructure;
import com.workshop.infrastructure.constructs.VSCodeIdeProps;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
// import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.DefaultStackSynthesizerProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.eks.CfnAccessEntry;
import software.amazon.awscdk.services.eks.CfnAccessEntry.AccessScopeProperty;
import software.amazon.awscdk.services.eks.CfnAccessEntry.AccessPolicyProperty;

import software.constructs.Construct;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class WorkshopStack extends Stack {

    public WorkshopStack(final Construct scope, final String id) {
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
            .managedPolicies(Arrays.asList(
                // ManagedPolicy.fromAwsManagedPolicyName("AdministratorAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("ReadOnlyAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore")
            ))
            .build();

        var policyDocumentJson = loadFile("/iam-policy.json");
        var policyDocument = PolicyDocument.fromJson(new JSONObject(policyDocumentJson).toMap());

        var policy = ManagedPolicy.Builder.create(this, "WorkshopIdeUserPolicy")
            .document(policyDocument)
            .build();

        ideRole.addManagedPolicy(policy);

        // Create security group for Unicorn application with access to DB, EKS, IDE
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

        createVSCodeIde(vpc, ideRole, unicornSecurityGroup);

        var workshopInfrastructure = new UnicornStoreInfrastructure(this, id, vpc, unicornSecurityGroup);
        workshopInfrastructure.getEventBridge().grantPutEventsTo(ideRole);
        workshopInfrastructure.getDatabaseSecret().grantRead(ideRole);
        workshopInfrastructure.getParamJdbc().grantRead(ideRole);

        var eksClusterName = "unicorn-store";
        var workshopEKSCluster = new EKSCluster(this, eksClusterName, vpc, unicornSecurityGroup, accountId);

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

        // var kubeconfigCommandOutput = CfnOutput.Builder.create(this, "KubeconfigCommand")
        //     .value(String.format("aws eks --region %s update-kubeconfig --name %s", region, eksClusterName))
        //     .description("Command to update kubeconfig")
        //     .build();
        // kubeconfigCommandOutput.getNode().addDependency(workshopEKSCluster);
    }

    private void createVSCodeIde(Vpc vpc, Role ideRole, SecurityGroup sg) {
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

        var ideProps = new VSCodeIdeProps();
        ideProps.setBootstrapTimeoutMinutes(30);
        ideProps.setBootstrapScript(bootstrapScript);
        ideProps.setRole(ideRole);
        ideProps.setVpc(vpc);
        ideProps.setAdditionalSecurityGroups(Arrays.asList(sg));
        ideProps.setAvailabilityZone(vpc.getAvailabilityZones().get(0));
        ideProps.setTerminalOnStartup(true);
        ideProps.setExtensions(Arrays.asList(
            // "amazonwebservices.aws-toolkit-vscode",
            // "amazonwebservices.amazon-q-vscode",
            "ms-azuretools.vscode-docker",
            "ms-kubernetes-tools.vscode-kubernetes-tools",
            "vscjava.vscode-java-pack"
        ));

        new VSCodeIde(this, "VSCodeIde", ideProps);
    }

        private String loadFile(String filePath) {
        try {
            return Files.readString(Path.of(getClass().getResource(filePath).getPath()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file " + filePath, e);
        }
    }
}
