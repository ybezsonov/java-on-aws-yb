package com.workshop.infrastructure;

import com.workshop.infrastructure.constructs.WorkshopVpc;
import software.constructs.Construct;
import software.amazon.awscdk.Stack;


public class WorkshopInfrastructureStack extends Stack {

    public WorkshopInfrastructureStack(final Construct scope, final String id) {
        super(scope, id);

        var workshopVpc = new WorkshopVpc(this, id);
    }
}
