package com.workshop.infrastructure.unicorn;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ArnPrincipal;
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

public class CoreInfrastructure extends Construct {

    private DatabaseSecret databaseSecret;
    private StringParameter paramJdbc;
    private EventBus eventBridge;
    private Repository ecrRepository;

    public DatabaseSecret getDatabaseSecret() {
        return databaseSecret;
    }
    public StringParameter getParamJdbc() {
        return paramJdbc;
    }
    public EventBus getEventBridge() {
        return eventBridge;
    }
    public Repository getEcrRepository() {
        return ecrRepository;
    }

    public CoreInfrastructure(final Construct scope, final String id, Vpc vpc) {
        super(scope, id);

        databaseSecret = createDatabaseSecret();
        var database = createDatabase(vpc, databaseSecret);
        var databaseUrl = database.getClusterEndpoint().getHostname();

        paramJdbc = StringParameter.Builder.create(this, "SsmParameterDatabaseJDBCConnectionString")
            .allowedPattern(".*")
            .description("databaseJDBCConnectionString")
            .parameterName("databaseJDBCConnectionString")
            .stringValue("jdbc:postgresql://" + databaseUrl + ":5432/unicorns")
            .tier(ParameterTier.STANDARD)
            .build();
        paramJdbc.getNode().addDependency(database);

        eventBridge = EventBus.Builder.create(this, "UnicornEventBus")
            .eventBusName("unicorns")
            .build();

        ecrRepository = Repository.Builder.create(this, "UnicornStoreEcr")
            .repositoryName("unicorn-store-spring")
            .imageScanOnPush(false)
            .removalPolicy(RemovalPolicy.DESTROY)
            .emptyOnDelete(true)  // This will force delete all images when repository is deleted
            .build();

        // Roles - AppRunner
        var unicornStoreApprunnerRole = Role.Builder.create(this, "UnicornStoreApprunnerRole")
            .roleName("unicornstore-apprunner-role")
            .assumedBy(new ServicePrincipal("tasks.apprunner.amazonaws.com")).build();
        unicornStoreApprunnerRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        eventBridge.grantPutEventsTo(unicornStoreApprunnerRole);
        databaseSecret.grantRead(unicornStoreApprunnerRole);
        paramJdbc.grantRead(unicornStoreApprunnerRole);

        var appRunnerECRAccessRole = Role.Builder.create(this, "UnicornStoreApprunnerEcrAccessRole")
            .roleName("unicornstore-apprunner-ecr-access-role")
            .assumedBy(new ServicePrincipal("build.apprunner.amazonaws.com")).build();
        appRunnerECRAccessRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreApprunnerEcrAccessRole-" + "AWSAppRunnerServicePolicyForECRAccess",
            "arn:aws:iam::aws:policy/service-role/AWSAppRunnerServicePolicyForECRAccess"));

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
            .roleName("unicornstore-ecs-task-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskRole-" + "CloudWatchLogsFullAccess",
            "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"));
        unicornStoreEscTaskRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskRole-" + "AmazonSSMReadOnlyAccess",
            "arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"));
        unicornStoreEscTaskRole.addToPolicy(AWSOpenTelemetryPolicy);

        eventBridge.grantPutEventsTo(unicornStoreEscTaskRole);
        databaseSecret.grantRead(unicornStoreEscTaskRole);
        paramJdbc.grantRead(unicornStoreEscTaskRole);

        Role unicornStoreEscTaskExecutionRole = Role.Builder.create(this, "UnicornStoreEcsTaskExecutionRole")
            .roleName("unicornstore-ecs-task-execution-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskExecutionRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("logs:CreateLogGroup"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskExecutionRole-" + "AmazonECSTaskExecutionRolePolicy",
            "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskExecutionRole-" + "CloudWatchLogsFullAccess",
            "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskExecutionRole-" + "AmazonSSMReadOnlyAccess",
            "arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"));
        unicornStoreEscTaskExecutionRole.addToPolicy(AWSOpenTelemetryPolicy);

        databaseSecret.grantRead(unicornStoreEscTaskExecutionRole);
        paramJdbc.grantRead(unicornStoreEscTaskExecutionRole);

        // Roles - EKS
        var dbSecretPolicy = ManagedPolicy.Builder.create(this, "UnicornStoreDbSecretsManagerPolicy")
            .managedPolicyName("unicornstore-db-secret-policy")
            .statements(List.of(
                PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of("secretsmanager:ListSecrets"))
                    .resources(List.of("*"))
                    .build(),
                PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of(
                            "secretsmanager:GetResourcePolicy",
                            "secretsmanager:DescribeSecret",
                            "secretsmanager:GetSecretValue",
                            "secretsmanager:ListSecretVersionIds"
                    ))
                    .resources(List.of(databaseSecret.getSecretFullArn()))
                    .build()
            ))
            .build();

        ServicePrincipal eksPods = new ServicePrincipal("pods.eks.amazonaws.com");
        // External Secrets Operator roles
        Role unicornStoreEksEsoRole = Role.Builder.create(this, "unicornstore-eks-eso-role")
            .roleName("unicornstore-eks-eso-role")
            .assumedBy(eksPods.withSessionTags())
            .build();
        ArnPrincipal unicornStoreEksEsoRolePrincipal = new ArnPrincipal(unicornStoreEksEsoRole.getRoleArn());

        Role unicornStoreEksEsoSmRole = Role.Builder.create(this, "unicornstore-eks-eso-sm-role")
            .roleName("unicornstore-eks-eso-sm-role")
            .assumedBy(unicornStoreEksEsoRolePrincipal.withSessionTags())
            .build();
        unicornStoreEksEsoSmRole.addManagedPolicy(dbSecretPolicy);

        // EKS Pod Identity role
        var unicornStoreEksPodRole = Role.Builder.create(this, "UnicornStoreEksPodRole")
            .roleName("unicornstore-eks-pod-role")
            .assumedBy(eksPods.withSessionTags())
            .build();
        unicornStoreEksPodRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        unicornStoreEksPodRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEksPodRole-" + "CloudWatchAgentServerPolicy",
            "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"));

        eventBridge.grantPutEventsTo(unicornStoreEksPodRole);
        databaseSecret.grantRead(unicornStoreEksPodRole);
        paramJdbc.grantRead(unicornStoreEksPodRole);
    }

    private DatabaseSecret createDatabaseSecret() {
        return DatabaseSecret.Builder
            .create(this, "postgres")
            .secretName("unicornstore-db-secret")
            .username("postgres").build();
    }

    private DatabaseCluster createDatabase(Vpc vpc, DatabaseSecret databaseSecret) {
        var databaseSecurityGroup = SecurityGroup.Builder.create(this, "DatabaseSecurityGroup")
            .securityGroupName("Database security Group")
            .description("Database security Group")
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

        var dbCluster = DatabaseCluster.Builder.create(this, "UnicornStoreDatabase")
            .engine(DatabaseClusterEngine.auroraPostgres(
                AuroraPostgresClusterEngineProps.builder().version(AuroraPostgresEngineVersion.VER_16_4).build()))
            .serverlessV2MinCapacity(0.5)
            .serverlessV2MaxCapacity(4)
            .writer(ClusterInstance.serverlessV2("writer"))
            .enableDataApi(true)
            .defaultDatabaseName("unicorns")
            .clusterIdentifier("UnicornStoreDatabaseCluster")
            .instanceIdentifierBase("UnicornStoreDatabaseInstance")
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build())
            .securityGroups(List.of(databaseSecurityGroup))
            .credentials(Credentials.fromSecret(databaseSecret))
            .build();

        return dbCluster;
    }
}
