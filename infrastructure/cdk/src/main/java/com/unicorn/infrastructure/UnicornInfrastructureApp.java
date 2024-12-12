package com.unicorn.infrastructure;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import java.util.List;

public class UnicornInfrastructureApp {

    public static void main(final String[] args) {
        App app = new App();

        var unicornInfrastructureStack = new UnicornInfrastructureStack(app, "UnicornInfrastructure");

        // Add CDK-NAG checks: https://github.com/cdklabs/cdk-nag
        // Add suppression to exclude certain findings that are not needed for Workshop environment
        Aspects.of(app).add(AwsSolutionsChecks.Builder.create().build());
        var suppressionInfrastructure = List.of(
            new NagPackSuppression.Builder().id("AwsSolutions-VPC7").reason("Workshop environment does not need VPC flow logs").build()
        );
        NagSuppressions.addStackSuppressions(unicornInfrastructureStack, suppressionInfrastructure);

        app.synth();
    }
}
