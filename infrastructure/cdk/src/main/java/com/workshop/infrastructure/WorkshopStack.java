package com.workshop.infrastructure;

import com.workshop.infrastructure.constructs.VSCodeIde;
import com.workshop.infrastructure.constructs.CustomVpc;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.DefaultStackSynthesizer;
import software.amazon.awscdk.DefaultStackSynthesizerProps;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.SecurityGroup;

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

        var workshopVpc = new CustomVpc(this, "WorkshopVpc");

        var ideProps = new VSCodeIde.VSCodeIdeProps();
        ideProps.setVpc(workshopVpc.getVpc());
        ideProps.setAvailabilityZone(workshopVpc.getVpc().getAvailabilityZones().get(0));
        // Create a security group to access an application at the port 8080
        SecurityGroup ideSecurityGroup = SecurityGroup.Builder.create(this, "AppSecurityGroup")
            .vpc(workshopVpc.getVpc())
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
            "amazonwebservices.aws-toolkit-vscode",
            // "amazonwebservices.amazon-q-vscode",
            "ms-azuretools.vscode-docker",
            "ms-kubernetes-tools.vscode-kubernetes-tools",
            "vscjava.vscode-java-pack"
        ));
        ideProps.setBootstrapScripts(Arrays.asList(
            "/bootstrapIde.sh",
            "/bootstrapUnicornStoreSpring.sh"
        ));

        var vsCodeIde = new VSCodeIde(this, "VSCodeIde", ideProps);

        CfnOutput.Builder.create(this, "IdeUrl")
            .value(vsCodeIde.getIdeUrl())
            .description("Workshop IDE Url")
            .build();
        CfnOutput.Builder.create(this, "IdePassword")
            .value(vsCodeIde.getIdePassword())
            .description("Workshop IDE Password")
            .build();
    }
}
