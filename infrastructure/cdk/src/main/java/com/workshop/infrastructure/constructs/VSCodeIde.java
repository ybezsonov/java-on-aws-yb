package com.workshop.infrastructure.constructs;

import software.amazon.awscdk.CfnWaitCondition;
import software.amazon.awscdk.CfnWaitConditionHandle;
import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.services.cloudfront.AllowedMethods;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.CachePolicy;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.HttpVersion;
import software.amazon.awscdk.services.cloudfront.OriginProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.OriginRequestPolicy;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.HttpOrigin;
import software.amazon.awscdk.services.cloudfront.origins.HttpOriginProps;
import software.amazon.awscdk.services.ec2.BlockDevice;
import software.amazon.awscdk.services.ec2.BlockDeviceVolume;
import software.amazon.awscdk.services.ec2.EbsDeviceOptions;
import software.amazon.awscdk.services.ec2.EbsDeviceVolumeType;
import software.amazon.awscdk.services.ec2.IMachineImage;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.Instance;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.MachineImage;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.iam.IManagedPolicy;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.amazon.awscdk.services.ssm.CfnDocument;

import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class VSCodeIde extends Construct {
    private final Instance ec2Instance;
    private final String ideUrl;

    private final IRole ideRole;
    private final Secret ideSecretsManagerPassword;
    private CustomResource passwordResource;

    public static class VSCodeIdeProps {
        private String instanceName = "IdeInstance";
        private List<String> bootstrapScripts = new ArrayList<>();
        private int diskSize = 50;
        private IVpc vpc;
        private String availabilityZone;
        private IMachineImage machineImage = MachineImage.latestAmazonLinux2023();
        private InstanceType instanceType = InstanceType.of(InstanceClass.T3, InstanceSize.MEDIUM);
        private String codeServerVersion = "4.95.3";
        private List<IManagedPolicy> additionalIamPolicies = new ArrayList<>();
        private List<ISecurityGroup> additionalSecurityGroups = new ArrayList<>();
        private int bootstrapTimeoutMinutes = 15;
        private boolean enableGitea = false;
        private String splashUrl = "";
        private String readmeUrl = "";
        private String environmentContentsZip = "";
        private List<String> extensions = new ArrayList<>();
        private boolean terminalOnStartup = false;
        private IRole role;

        public List<String> getBootstrapScripts() { return bootstrapScripts; }
        public void setBootstrapScripts(List<String> bootstrapScripts) { this.bootstrapScripts = bootstrapScripts; }
        
        public int getDiskSize() { return diskSize; }
        public void setDiskSize(int diskSize) { this.diskSize = diskSize; }
        
        public IVpc getVpc() { return vpc; }
        public void setVpc(IVpc vpc) { this.vpc = vpc; }
        
        public String getAvailabilityZone() { return availabilityZone; }
        public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
        
        public IMachineImage getMachineImage() { return machineImage; }
        public void setMachineImage(IMachineImage machineImage) { this.machineImage = machineImage; }
        
        public InstanceType getInstanceType() { return instanceType; }
        public void setInstanceType(InstanceType instanceType) { this.instanceType = instanceType; }
        
        public String getCodeServerVersion() { return codeServerVersion; }
        public void setCodeServerVersion(String codeServerVersion) { this.codeServerVersion = codeServerVersion; }
        
        public List<IManagedPolicy> getAdditionalIamPolicies() { return additionalIamPolicies; }
        public void setAdditionalIamPolicies(List<IManagedPolicy> additionalIamPolicies) { this.additionalIamPolicies = additionalIamPolicies; }
        
        public List<ISecurityGroup> getAdditionalSecurityGroups() { return additionalSecurityGroups; }
        public void setAdditionalSecurityGroups(List<ISecurityGroup> additionalSecurityGroups) { this.additionalSecurityGroups = additionalSecurityGroups; }
        
        public int getBootstrapTimeoutMinutes() { return bootstrapTimeoutMinutes; }
        public void setBootstrapTimeoutMinutes(int bootstrapTimeoutMinutes) { this.bootstrapTimeoutMinutes = bootstrapTimeoutMinutes; }
        
        public boolean isEnableGitea() { return enableGitea; }
        public void setEnableGitea(boolean enableGitea) { this.enableGitea = enableGitea; }
        
        public String getSplashUrl() { return splashUrl; }
        public void setSplashUrl(String splashUrl) { this.splashUrl = splashUrl; }
        
        public String getReadmeUrl() { return readmeUrl; }
        public void setReadmeUrl(String readmeUrl) { this.readmeUrl = readmeUrl; }
        
        public String getEnvironmentContentsZip() { return environmentContentsZip; }
        public void setEnvironmentContentsZip(String environmentContentsZip) { this.environmentContentsZip = environmentContentsZip; }
        
        public List<String> getExtensions() { return extensions; }
        public void setExtensions(List<String> extensions) { this.extensions = extensions; }
        
        public boolean isTerminalOnStartup() { return terminalOnStartup; }
        public void setTerminalOnStartup(boolean terminalOnStartup) { this.terminalOnStartup = terminalOnStartup; }
        
        public IRole getRole() { return role; }
        public void setRole(IRole role) { this.role = role; }
    }

    public VSCodeIde(final Construct scope, final String id, final VSCodeIdeProps props) {
        super(scope, id);

        if (props.vpc == null) {
            var ideVpc = new CustomVpc(this, "IdeVpc");
            props.vpc = ideVpc.getVpc();
        }

        // Create prefix list function and resource
        Function prefixListFunction = Function.Builder.create(this, "IdePrefixListFunction")
            .code(Code.fromInline(loadFile("/prefix-lambda.py")))
            .handler("index.lambda_handler")
            .runtime(Runtime.PYTHON_3_13)
            .timeout(Duration.minutes(3))
            .build();

        prefixListFunction.addToRolePolicy(PolicyStatement.Builder.create()
            .resources(List.of("*"))
            .actions(List.of("ec2:DescribeManagedPrefixLists"))
            .build());

        CustomResource prefixListResource = CustomResource.Builder.create(this, "IdePrefixListResource")
            .serviceToken(prefixListFunction.getFunctionArn())
            .build();

        // Set up IAM role
        if (props.role != null) {
            this.ideRole = props.role;
        } else {
            this.ideRole = Role.Builder.create(this, "IdeRole")
                .assumedBy(ServicePrincipal.Builder.create("ec2.amazonaws.com").build())
                .build();
        }

        // Add managed policies
        List<IManagedPolicy> policies = new ArrayList<>();
        policies.add(ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"));
        policies.addAll(props.additionalIamPolicies);
        policies.forEach(policy -> this.ideRole.addManagedPolicy(policy));

        // Create security group
        SecurityGroup ideSecurityGroup = SecurityGroup.Builder.create(this, "IdeSecurityGroup")
            .vpc(props.vpc)
            .allowAllOutbound(true)
            .description("IDE security group")
            .build();

        ideSecurityGroup.addIngressRule(
            Peer.prefixList(prefixListResource.getAttString("PrefixListId")),
            Port.tcp(80),
            "HTTP from CloudFront only"
        );

        if (props.enableGitea) {
            ideSecurityGroup.addIngressRule(
                Peer.ipv4(props.vpc.getVpcCidrBlock()),
                Port.tcp(9999),
                "Gitea API from VPC"
            );
            ideSecurityGroup.addIngressRule(
                Peer.ipv4(props.vpc.getVpcCidrBlock()),
                Port.tcp(2222),
                "Gitea SSH from VPC"
            );
        }

        // Create EC2 instance
        this.ec2Instance = Instance.Builder.create(this, id)
            .instanceName(props.instanceName)
            .vpc(props.vpc)
            .machineImage(props.machineImage)
            .instanceType(props.instanceType)
            .role(this.ideRole)
            .securityGroup(ideSecurityGroup)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PUBLIC)
                .build())
            .blockDevices(List.of(BlockDevice.builder()
                .deviceName("/dev/xvda")
                .volume(BlockDeviceVolume.ebs(props.diskSize, EbsDeviceOptions.builder()
                    .volumeType(EbsDeviceVolumeType.GP3)
                    .deleteOnTermination(true)
                    .encrypted(true)
                    .build()))
                .build()))
            .build();

        this.ec2Instance.getNode().addDependency(props.vpc);

        // Add additional security groups if any
        props.additionalSecurityGroups.forEach(sg -> this.ec2Instance.addSecurityGroup(sg));

        // Create bootstrap function
        Function bootstrapFunction = Function.Builder.create(this, "IdeBootstrapFunction")
            .code(Code.fromInline(loadFile("/lambda.py")))
            .handler("index.lambda_handler")
            .runtime(Runtime.PYTHON_3_13)
            .timeout(Duration.minutes(15))
            .build();

        bootstrapFunction.addToRolePolicy(PolicyStatement.Builder.create()
            .resources(List.of(this.ideRole.getRoleArn()))
            .actions(List.of("iam:PassRole"))
            .build());

        bootstrapFunction.addToRolePolicy(PolicyStatement.Builder.create()
            .resources(List.of("*"))
            .actions(List.of(
                "ec2:DescribeInstances",
                "iam:ListInstanceProfiles",
                "ssm:DescribeInstanceInformation",
                "ssm:SendCommand",
                "ssm:GetCommandInvocation"
            ))
            .build());

        // Set up logging
        LogGroup logGroup = LogGroup.Builder.create(this, "IdeLogGroup")
            .retention(RetentionDays.ONE_WEEK)
            .build();
        logGroup.grantWrite(this.ideRole);

        // Create password secret
        this.ideSecretsManagerPassword = Secret.Builder.create(this, "IdePasswordSecret")
            .generateSecretString(SecretStringGenerator.builder()
                .excludePunctuation(true)
                .passwordLength(32)
                .generateStringKey("password")
                .includeSpace(false)
                .secretStringTemplate("{\"password\":\"\"}")
                .excludeCharacters("\"@/\\\\")
                .build())
            .build();
        this.ideSecretsManagerPassword.grantRead(this.ideRole);

        // Create CloudFront distribution
        Distribution distribution = Distribution.Builder.create(this, "IdeDistribution")
            .defaultBehavior(BehaviorOptions.builder()
                .origin(new HttpOrigin(this.ec2Instance.getInstancePublicDnsName(),
                    HttpOriginProps.builder()
                        .protocolPolicy(OriginProtocolPolicy.HTTP_ONLY)
                        .httpPort(80)
                        .build()))
                .allowedMethods(AllowedMethods.ALLOW_ALL)
                .cachePolicy(CachePolicy.CACHING_DISABLED)
                .originRequestPolicy(OriginRequestPolicy.ALL_VIEWER)
                .viewerProtocolPolicy(ViewerProtocolPolicy.ALLOW_ALL)
                .build())
            .httpVersion(HttpVersion.HTTP2)
            .build();

        // Set up wait condition
        CfnWaitConditionHandle waitHandle = CfnWaitConditionHandle.Builder.create(this, "IdeBootstrapWaitConditionHandle")
            .build();

        CfnWaitCondition waitCondition = CfnWaitCondition.Builder.create(this, "IdeBootstrapWaitCondition")
            .count(1)
            .handle(waitHandle.getRef())
            .timeout(String.valueOf(props.bootstrapTimeoutMinutes * 60))
            .build();
        waitCondition.getNode().addDependency(this.ec2Instance);

        // Create SSM document
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("BootstrapScript", Map.of(
            "type", "String",
            "description", "(Optional) Custom bootstrap script to run.",
            "default", ""
        ));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("runCommand", Arrays.asList(
            Fn.sub(loadFile("/bootstrapDocument.sh"), Map.ofEntries(
                Map.entry("instanceIamRoleName", ideRole.getRoleName()),
                Map.entry("instanceIamRoleArn", ideRole.getRoleArn()),
                Map.entry("passwordName", ideSecretsManagerPassword.getSecretName()),
                Map.entry("domain", distribution.getDistributionDomainName()),
                Map.entry("codeServerVersion", props.getCodeServerVersion()),
                Map.entry("waitConditionHandleUrl", waitHandle.getRef()),
                Map.entry("customBootstrapScripts", loadBootstrapScripts(props.getBootstrapScripts())),
                Map.entry("installGitea", addGiteaToSSMTemplate(props.enableGitea)),
                Map.entry("splashUrl", props.getSplashUrl()),
                Map.entry("readmeUrl", props.getReadmeUrl()),
                Map.entry("environmentContentsZip", props.getEnvironmentContentsZip()),
                Map.entry("extensions", String.join(",", props.getExtensions())),
                Map.entry("terminalOnStartup", String.valueOf(props.isTerminalOnStartup()))
            ))
        ));

        Map<String, Object> mainStep = new HashMap<>();
        mainStep.put("action", "aws:runShellScript");
        mainStep.put("name", "IdeBootstrapFunction");
        mainStep.put("inputs", inputs);

        Map<String, Object> content = new HashMap<>();
        content.put("schemaVersion", "2.2");
        content.put("description", "Bootstrap IDE");
        content.put("parameters", parameters);
        content.put("mainSteps", Arrays.asList(mainStep));

        CfnDocument ssmDocument = CfnDocument.Builder.create(this, "IdeBootstrapDocument")
            .documentType("Command")
            .documentFormat("YAML")
            .updateMethod("NewVersion")
            .content(content)
            .build();
        waitCondition.getNode().addDependency(ssmDocument);

        // Create bootstrap resource
        CustomResource.Builder.create(this, "IdeBootstrapResource")
            .serviceToken(bootstrapFunction.getFunctionArn())
            .properties(Map.of(
                "InstanceId", this.ec2Instance.getInstanceId(),
                "SsmDocument", ssmDocument.getRef(),
                "LogGroupName", logGroup.getLogGroupName()
            ))
            .build();

        this.ideUrl = "https://" + distribution.getDistributionDomainName();
    }

    public String getIdeUrl() { return ideUrl; }

    public String getIdePassword() {
        if (passwordResource == null) {
            Function passwordFunction = Function.Builder.create(this, "IdePasswordExporterFunction")
                .code(Code.fromInline(loadFile("/password.py")))
                .handler("index.lambda_handler")
                .runtime(Runtime.PYTHON_3_13)
                .timeout(Duration.minutes(3))
                .build();

            ideSecretsManagerPassword.grantRead(passwordFunction);

            passwordResource = CustomResource.Builder.create(this, "IdePasswordExporter")
                .serviceToken(passwordFunction.getFunctionArn())
                .properties(Map.of(
                    "PasswordName", ideSecretsManagerPassword.getSecretName()
                ))
                .build();
        }

        return passwordResource.getAtt("password").toString();
    }

    private String loadFile(String filePath) {
        try {
            return Files.readString(Path.of(getClass().getResource(filePath).getPath()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file " + filePath, e);
        }
    }

    private String addGiteaToSSMTemplate(Boolean enableGitea) {
        if (!enableGitea) {
            return "echo bootstrapGitea was not provided";
        }
        else {
            return loadFile("/bootstrapGitea.sh");
        }
    }

    private String loadBootstrapScripts(List<String> bootstrapScripts) {
        if (bootstrapScripts.isEmpty()) {
            return "echo bootstrapScripts were not provided";
        } else {
            StringBuilder sb = new StringBuilder();
            for (String script : bootstrapScripts) {
                sb.append("\necho \"Running " + script + " ...\"\n\n");
                sb.append(loadFile(script));
                sb.append("\necho \"Finished " + script + ".\"\n");
            }
            return sb.toString();
        }
    }
}