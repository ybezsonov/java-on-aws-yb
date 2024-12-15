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
import software.amazon.awscdk.services.rds.ClusterInstanceProps;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.DatabaseSecret;
import software.amazon.awscdk.services.ssm.ParameterTier;
import software.amazon.awscdk.services.ssm.StringParameter;

import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;

import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

public class WorkshopInfrastructure extends Construct {

    public WorkshopInfrastructure(final Construct scope, final String id, final Vpc vpc) {
        super(scope, id);

        var databaseSecret = createDatabaseSecret();
        var databaseUrl = createDatabase(vpc, databaseSecret);
        
        var paramJdbc = StringParameter.Builder.create(this, "SsmParameterDatabaseJDBCConnectionString")
            .allowedPattern(".*")
            .description("databaseJDBCConnectionString")
            .parameterName("databaseJDBCConnectionString")
            .stringValue(getDatabaseJDBCConnectionString(databaseUrl))
            .tier(ParameterTier.STANDARD)
            .build();

        var eventBridge = createEventBus();

        var unicornStoreECR = Repository.Builder.create(this, "unicornstore-ecr")
            .repositoryName("unicorn-store-spring")
            .imageScanOnPush(false)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // Roles - AppRunner
        var unicornStoreApprunnerRole = Role.Builder.create(this, "unicornstore-apprunner-role")
            .roleName("unicornstore-apprunner-role")
            .assumedBy(new ServicePrincipal("tasks.apprunner.amazonaws.com")).build();
        unicornStoreApprunnerRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        eventBridge.grantPutEventsTo(unicornStoreApprunnerRole);
        databaseSecret.grantRead(unicornStoreApprunnerRole);
        paramJdbc.grantRead(unicornStoreApprunnerRole);
        
        var appRunnerECRAccessRole = Role.Builder.create(this, "unicornstore-apprunner-ecr-access-role")
            .roleName("unicornstore-apprunner-ecr-access-role")
            .assumedBy(new ServicePrincipal("build.apprunner.amazonaws.com")).build();
        appRunnerECRAccessRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-apprunner-ecr-access-role-" + "AWSAppRunnerServicePolicyForECRAccess",
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
        
        var unicornStoreEscTaskRole = Role.Builder.create(this, "unicornstore-ecs-task-role")
            .roleName("unicornstore-ecs-task-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-ecs-task-role-" + "CloudWatchLogsFullAccess",
            "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"));
        unicornStoreEscTaskRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-ecs-task-role-" + "AmazonSSMReadOnlyAccess",
            "arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"));
        unicornStoreEscTaskRole.addToPolicy(AWSOpenTelemetryPolicy);

        Role unicornStoreEscTaskExecutionRole = Role.Builder.create(this, "unicornstore-ecs-task-execution-role")
            .roleName("unicornstore-ecs-task-execution-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskExecutionRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("logs:CreateLogGroup"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-ecs-task-execution-role-" + "AmazonECSTaskExecutionRolePolicy",
            "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-ecs-task-execution-role-" + "CloudWatchLogsFullAccess",
            "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-ecs-task-execution-role-" + "AmazonSSMReadOnlyAccess",
            "arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"));
        unicornStoreEscTaskExecutionRole.addToPolicy(AWSOpenTelemetryPolicy);

        eventBridge.grantPutEventsTo(unicornStoreEscTaskRole);        
        databaseSecret.grantRead(unicornStoreEscTaskRole);
        paramJdbc.grantRead(unicornStoreEscTaskRole);

        databaseSecret.grantRead(unicornStoreEscTaskExecutionRole);
        paramJdbc.grantRead(unicornStoreEscTaskExecutionRole);

        // Roles - EKS
        var eksPods = new ServicePrincipal("pods.eks.amazonaws.com");
        var unicornStoreEksPodRole = Role.Builder.create(this, "unicornstore-eks-pod-role")
            .roleName("unicornstore-eks-pod-role")
            .assumedBy(eksPods.withSessionTags())
            .build();
        unicornStoreEksPodRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        unicornStoreEksPodRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "unicornstore-eks-pod-role-" + "CloudWatchAgentServerPolicy",
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

    private String createDatabase(Vpc vpc, DatabaseSecret databaseSecret) {
        var databaseSecurityGroup = createDatabaseSecurityGroup(vpc);
        
        var cluster = DatabaseCluster.Builder.create(this, "UnicornDatabase")
            .engine(DatabaseClusterEngine.auroraPostgres(AuroraPostgresClusterEngineProps.builder().version(AuroraPostgresEngineVersion.VER_16_4).build()))
            .serverlessV2MinCapacity(0.5)
            .serverlessV2MaxCapacity(4)
            .writer(ClusterInstance.serverlessV2("writer"))        
            .enableDataApi(true)
            .defaultDatabaseName("unicorns")
            .instanceIdentifierBase("UnicornDatabaseInstance")
            .vpc(vpc)                
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build())
            .securityGroups(List.of(databaseSecurityGroup))
            .credentials(Credentials.fromSecret(databaseSecret))
            .build();

            var url = cluster.getClusterEndpoint().getHostname();
            var port = cluster.getClusterEndpoint().getPort();
            // return enpoint url
            return String.format("%s:%s", url, port.toString());
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
        return "jdbc-secretsmanager:postgresql://" + databaseUrl + ":5432/unicorns";
        // return "jdbc:postgresql://" + databaseSecret.secretValueFromJson("host").toString() + ":5432/unicorns";
    }

    private EventBus createEventBus() {
        return EventBus.Builder.create(this, "UnicornEventBus")
            .eventBusName("unicorns")
            .build();
    }
}
