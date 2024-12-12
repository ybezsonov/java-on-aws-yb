package com.workshop.infrastructure.constructs;

import software.constructs.Construct;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.IVpc;

public class WorkshopVpc extends Construct {

    private final IVpc vpc;

    public WorkshopVpc(final Construct scope, final String id) {
        super(scope, id);

        vpc = Vpc.Builder.create(this, "WorkshopVpc")
        .vpcName("WorkshopVPC")
        .build();
    }

    public IVpc getVpc() {
        return vpc;
    }
}
