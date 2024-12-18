package com.workshop.infrastructure.constructs;

import software.constructs.Construct;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.IpAddresses;
import java.util.Arrays;

public class WorkshopVpc extends Construct {

    private final Vpc vpc;

    public WorkshopVpc(final Construct scope, final String id) {
        super(scope, id);

    vpc = Vpc.Builder.create(this, "WorkshopVpc")
        .vpcName(id)
        .ipAddresses(IpAddresses.cidr("10.0.0.0/16"))
        .maxAzs(2)  // Use 2 Availability Zones
        .subnetConfiguration(Arrays.asList(
            SubnetConfiguration.builder()
                .name("Public")
                .subnetType(SubnetType.PUBLIC)
                .cidrMask(24)
                .build(),
            SubnetConfiguration.builder()
                .name("Private")
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .cidrMask(24)
                .build()
            ))
        .natGateways(1)
        .build();
    }

    public Vpc getVpc() {
        return vpc;
    }
}
