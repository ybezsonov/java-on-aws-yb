package com.workshop.infrastructure.constructs;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.AuroraPostgresClusterEngineProps;
import software.amazon.awscdk.services.rds.AuroraPostgresEngineVersion;
import software.amazon.awscdk.services.rds.ClusterInstance;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.DatabaseSecret;
import software.amazon.awscdk.services.ssm.ParameterTier;
import software.amazon.awscdk.services.ssm.StringParameter;

import software.constructs.Construct;

import java.util.List;

public class UnicornStoreInfrastructure extends Construct {

    private DatabaseSecret databaseSecret;
    private StringParameter paramJdbc;
    private EventBus eventBridge;

    public DatabaseSecret getDatabaseSecret() {
        return databaseSecret;
    }
    public StringParameter getParamJdbc() {
        return paramJdbc;
    }
    public EventBus getEventBridge() {
        return eventBridge;
    }

    public UnicornStoreInfrastructure(final Construct scope, final String id, final Vpc vpc) {
        super(scope, id);

        databaseSecret = createDatabaseSecret();
        var databaseUrl = createDatabase(vpc, databaseSecret);

        paramJdbc = StringParameter.Builder.create(this, "SsmParameterDatabaseJDBCConnectionString")
            .allowedPattern(".*")
            .description("databaseJDBCConnectionString")
            .parameterName("databaseJDBCConnectionString")
            .stringValue(getDatabaseJDBCConnectionString(databaseUrl))
            .tier(ParameterTier.STANDARD)
            .build();

        eventBridge = createEventBus();

        Repository.Builder.create(this, "UnicornStoreEcr")
            .repositoryName("unicorn-store-spring")
            .imageScanOnPush(false)
            .removalPolicy(RemovalPolicy.DESTROY)
            .emptyOnDelete(true)  // This will force delete all images when repository is deleted
            .build();

        // Roles - AppRunner
        var unicornStoreApprunnerRole = Role.Builder.create(this, "UnicornStoreApprunnerRole")
            .roleName("unicorn-store-apprunner-role")
            .assumedBy(new ServicePrincipal("tasks.apprunner.amazonaws.com")).build();
        unicornStoreApprunnerRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        eventBridge.grantPutEventsTo(unicornStoreApprunnerRole);
        databaseSecret.grantRead(unicornStoreApprunnerRole);
        paramJdbc.grantRead(unicornStoreApprunnerRole);

        var appRunnerECRAccessRole = Role.Builder.create(this, "UnicornStoreApprunnerEcrAccessRole")
            .roleName("unicorn-store-apprunner-ecr-access-role")
            .assumedBy(new ServicePrincipal("build.apprunner.amazonaws.com")).build();
        appRunnerECRAccessRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("AWSAppRunnerServicePolicyForECRAccess"));

        // Roles - ECS
        var AWSOpenTelemetryPolicy = PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(List.of("logs:PutLogEvents", "logs:CreateLogGroup", "logs:CreateLogStream",
                    "logs:DescribeLogStreams", "logs:DescribeLogGroups",
                    "logs:PutRetentionPolicy", "xray:PutTraceSegments",
                    "xray:PutTelemetryRecords", "xray:GetSamplingRules",
                    "xray:GetSamplingTargets", "xray:GetSamplingStatisticSummaries",
                    "cloudwatch:PutMetricData", "ssm:GetParameters"))
            .resources(List.of("*")).build();

        var unicornStoreEscTaskRole = Role.Builder.create(this, "UnicornStoreEcsTaskRole")
            .roleName("unicorn-store-ecs-task-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"));
        unicornStoreEscTaskRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMReadOnlyAccess"));

        eventBridge.grantPutEventsTo(unicornStoreEscTaskRole);
        databaseSecret.grantRead(unicornStoreEscTaskRole);
        paramJdbc.grantRead(unicornStoreEscTaskRole);

        Role unicornStoreEscTaskExecutionRole = Role.Builder.create(this, "UnicornStoreEcsTaskExecutionRole")
            .roleName("unicorn-store-ecs-task-execution-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskExecutionRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("logs:CreateLogGroup"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskExecutionRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("AmazonECSTaskExecutionRolePolicy"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMReadOnlyAccess"));

        databaseSecret.grantRead(unicornStoreEscTaskExecutionRole);
        paramJdbc.grantRead(unicornStoreEscTaskExecutionRole);

        // Roles - EKS
        var eksPods = new ServicePrincipal("pods.eks.amazonaws.com");
        var unicornStoreEksPodRole = Role.Builder.create(this, "UnicornStoreEksPodRole")
            .roleName("unicorn-store-eks-pod-role")
            .assumedBy(eksPods.withSessionTags())
            .build();
        unicornStoreEksPodRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
            unicornStoreEksPodRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy"));

        eventBridge.grantPutEventsTo(unicornStoreEksPodRole);
        databaseSecret.grantRead(unicornStoreEksPodRole);
        paramJdbc.grantRead(unicornStoreEksPodRole);
    }

    private DatabaseSecret createDatabaseSecret() {
        return DatabaseSecret.Builder
            .create(this, "postgres")
            .secretName("unicorn-store-db-secret")
            .username("postgres").build();
    }

    private String createDatabase(Vpc vpc, DatabaseSecret databaseSecret) {
        var databaseSecurityGroup = createDatabaseSecurityGroup(vpc);

        var cluster = DatabaseCluster.Builder.create(this, "UnicornStoreDatabase")
            .engine(DatabaseClusterEngine.auroraPostgres(
                AuroraPostgresClusterEngineProps.builder().version(AuroraPostgresEngineVersion.VER_16_4).build()))
            .serverlessV2MinCapacity(0.5)
            .serverlessV2MaxCapacity(4)
            .writer(ClusterInstance.serverlessV2("writer"))
            .enableDataApi(true)
            .defaultDatabaseName("unicorn-store")
            .clusterIdentifier("unicorn-store-database")
            .instanceIdentifierBase("unicorn-store-database-instance")
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build())
            .securityGroups(List.of(databaseSecurityGroup))
            .credentials(Credentials.fromSecret(databaseSecret))
            .build();

            return cluster.getClusterEndpoint().getHostname();
        }

    private SecurityGroup createDatabaseSecurityGroup(Vpc vpc) {
        var databaseSecurityGroup = SecurityGroup.Builder.create(this, "DatabaseSecurityGroup")
            .securityGroupName("Database Security Group")
            .allowAllOutbound(false)
            .vpc(vpc)
            .build();

        databaseSecurityGroup.addEgressRule(
            Peer.anyIpv4(),
            Port.tcp(5432),
            "Allow outbound PostgreSQL responses");

        databaseSecurityGroup.addIngressRule(
            Peer.ipv4("10.0.0.0/16"),
            Port.tcp(5432),
            "Allow Database Traffic from VPC");

        return databaseSecurityGroup;
    }

    public String getDatabaseJDBCConnectionString(String databaseUrl){
        return "jdbc-secretsmanager:postgresql://" + databaseUrl + ":5432/unicorn-store";
        // return "jdbc:postgresql://" + databaseUrl + ":5432/unicorn-store";
    }

    private EventBus createEventBus() {
        return EventBus.Builder.create(this, "UnicornEventBus")
            .eventBusName("unicorns")
            .build();
    }
}
