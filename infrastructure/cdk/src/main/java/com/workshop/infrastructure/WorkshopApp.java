package com.workshop.infrastructure;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import java.util.List;

public class WorkshopApp {

    public static void main(final String[] args) {
        App app = new App();

        var workshopStack = new WorkshopStack(app, "workshop-infrastructure");

        // Add CDK-NAG checks: https://github.com/cdklabs/cdk-nag
        // Add suppression to exclude certain findings that are not needed for Workshop environment
        Aspects.of(app).add(AwsSolutionsChecks.Builder.create().build());
        var suppressionWorkshop = List.of(
            new NagPackSuppression.Builder().id("AwsSolutions-VPC7").reason("Workshop environment does not need VPC flow logs").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-EC23").reason("ELB is accessible from the Internet to allow application testing" ).build(),
            new NagPackSuppression.Builder().id("AwsSolutions-IAM4").reason("AWS Managed policies are acceptable for the workshop" ).build(),
            new NagPackSuppression.Builder().id("AwsSolutions-IAM5").reason("Workshop environment use CDK default execution role for Lambdas" ).build(),
            new NagPackSuppression.Builder().id("AwsSolutions-EC28").reason("Workshop instance doesn't need autoscaling").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-EC29").reason("Workshop instance doesn't need autoscaling").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-SMG4").reason("Ephemeral workshop environment does not need to rotate secrets").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-CFR1").reason("Workshop environment should be accessible from any Geo").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-CFR2").reason("Ephemeral workshop environment does not need WAF").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-CFR3").reason("Ephemeral workshop environment does not need logging").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-CFR4").reason("Workshop instance uses http").build(),
            new NagPackSuppression.Builder().id("AwsSolutions-CFR5").reason("Workshop instance uses http").build()
            
        );
        NagSuppressions.addStackSuppressions(workshopStack, suppressionWorkshop);

        app.synth();
    }
}
