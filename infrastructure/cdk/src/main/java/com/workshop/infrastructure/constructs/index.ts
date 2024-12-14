// import * as cdk from 'aws-cdk-lib';
import { Construct, IDependable } from "constructs";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as lambda from "aws-cdk-lib/aws-lambda";
import * as iam from "aws-cdk-lib/aws-iam";
import * as ssm from "aws-cdk-lib/aws-ssm";
import * as logs from "aws-cdk-lib/aws-logs";
import * as secretsmanager from "aws-cdk-lib/aws-secretsmanager";
import { IdeVpc } from "@workshop-cdk-constructs/ide-vpc";
import * as cdk from "aws-cdk-lib";
import path = require("path");
import * as cfn from "aws-cdk-lib/aws-cloudfront";
import * as cfn_origins from "aws-cdk-lib/aws-cloudfront-origins";
import * as fs from "fs";

const ssmDocumentTemplate = fs.readFileSync(
  path.join(__dirname, "../resources/bootstrapDocument.sh"),
  { encoding: "utf-8" }
);
const bootstrapLambda = fs.readFileSync(
  path.join(__dirname, "../resources/lambda.py"),
  { encoding: "utf-8" }
);
const passwordLambda = fs.readFileSync(
  path.join(__dirname, "../resources/password.py"),
  { encoding: "utf-8" }
);
const prefixListLambda = fs.readFileSync(
  path.join(__dirname, "../resources/prefix-lambda.py"),
  { encoding: "utf-8" }
);
const GiteaVsCodeBootstrapScript = fs.readFileSync(
  path.join(__dirname, "../resources/bootstrapGitea.sh"),
  { encoding: "utf-8" }
);

export interface VSCodeIdeProps {
  bootstrapScript?: string;
  diskSize?: number;
  vpc?: ec2.Vpc;
  availabilityZone?: string;
  machineImage?: ec2.IMachineImage;
  instanceType?: ec2.InstanceType;
  codeServerVersion?: string;
  additionalIamPolicies?: iam.IManagedPolicy[];
  additionalSecurityGroups?: ec2.SecurityGroup[] | [];
  bootstrapTimeoutMinutes?: number;
  enableGitea?: boolean;
  splashUrl?: string;
  readmeUrl?: string;
  environmentContentsZip?: string;
  extensions?: string[];
  exportIdePassword?: boolean;
  terminalOnStartup?: boolean;
  role?: iam.IRole;
}

export let defaultProps: Partial<VSCodeIdeProps> = {
  bootstrapScript: 'echo "NOOP"',
  diskSize: 30,
  vpc: undefined,
  machineImage: ec2.MachineImage.latestAmazonLinux2023(),
  instanceType: ec2.InstanceType.of(
    ec2.InstanceClass.T3,
    ec2.InstanceSize.MEDIUM
  ),
  codeServerVersion: "4.91.1",
  additionalIamPolicies: [],
  additionalSecurityGroups: [],
  bootstrapTimeoutMinutes: 15,
  enableGitea: false,
  splashUrl: "",
  readmeUrl: "",
  environmentContentsZip: "",
  extensions: [],
  exportIdePassword: true,
  terminalOnStartup: false,
};

export class VSCodeIde extends Construct {
  public readonly ec2Instance: ec2.Instance;
  /**
   * @deprecated Set exportIdePassword=false and use getIdePassword() instead
   */
  public readonly idePassword: string | undefined;
  public readonly accessUrl: string;
  public readonly ideRole: iam.IRole;
  public readonly bootstrapped: IDependable;

  private readonly ideSecretsManagerPassword: secretsmanager.Secret;

  private passwordResource: cdk.CustomResource;

  constructor(scope: Construct, id: string, props: VSCodeIdeProps) {
    super(scope, id);

    let options: VSCodeIdeProps = { ...(defaultProps as any), ...props };

    if (!options.vpc) {
      let ideVpc = new IdeVpc(this, "IdeVpc", {
        availabilityZone: options.availabilityZone,
      });
      options.vpc = ideVpc.vpc;
    }

    const prefixListFunction = new lambda.Function(
      this,
      "IdePrefixListFunction",
      {
        code: lambda.Code.fromInline(prefixListLambda),
        handler: "index.lambda_handler",
        runtime: lambda.Runtime.PYTHON_3_12,
        timeout: cdk.Duration.minutes(3),
      }
    );
    prefixListFunction.addToRolePolicy(
      new iam.PolicyStatement({
        resources: ["*"],
        actions: ["ec2:DescribeManagedPrefixLists"],
      })
    );

    const prefixListResource = new cdk.CustomResource(
      this,
      "IdePrefixListResource",
      {
        serviceToken: prefixListFunction.functionArn,
      }
    );

    if (options.role) {
      this.ideRole = options.role;
    } else {
      this.ideRole = new iam.Role(this, "IdeRole", {
        assumedBy: new iam.ServicePrincipal("ec2.amazonaws.com"),
      });
    }

    [iam.ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore")]
      .concat(options.additionalIamPolicies)
      .forEach((policy) => {
        this.ideRole.addManagedPolicy(policy);
      });

    const ideSecurityGroup = new ec2.SecurityGroup(this, `IdeSecurityGroup`, {
      vpc: options.vpc,
      allowAllOutbound: true,
      description: "IDE security group",
    });
    ideSecurityGroup.addIngressRule(
      ec2.Peer.prefixList(prefixListResource.getAttString("PrefixListId")),
      ec2.Port.tcp(80),
      "HTTP from CloudFront only"
    );
    if (options.enableGitea) {
      ideSecurityGroup.addIngressRule(
        ec2.Peer.ipv4(options.vpc.vpcCidrBlock),
        ec2.Port.tcp(9999),
        "Gitea API from VPC"
      );
      ideSecurityGroup.addIngressRule(
        ec2.Peer.ipv4(options.vpc.vpcCidrBlock),
        ec2.Port.tcp(2222),
        "Gitea SSH from VPC"
      );
    }

    this.ec2Instance = new ec2.Instance(this, id, {
      vpc: options.vpc,
      machineImage: options.machineImage,
      instanceType: options.instanceType,
      role: this.ideRole,
      securityGroup: ideSecurityGroup,
      associatePublicIpAddress: true,
      userDataCausesReplacement: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      blockDevices: [
        {
          deviceName: "/dev/xvda",
          volume: {
            ebsDevice: {
              volumeSize: options.diskSize,
              volumeType: ec2.EbsDeviceVolumeType.GP3,
              deleteOnTermination: true,
              encrypted: true,
            },
          },
        },
      ],
    });
    this.ec2Instance.node.addDependency(options.vpc);

    if (options.additionalSecurityGroups.length > 0) {
      for (let securityGroup of options.additionalSecurityGroups) {
        this.ec2Instance.addSecurityGroup(securityGroup);
      }
    }

    const bootstrapFunction = new lambda.Function(
      this,
      "IdeBootstrapFunction",
      {
        code: lambda.Code.fromInline(bootstrapLambda),
        handler: "index.lambda_handler",
        runtime: lambda.Runtime.PYTHON_3_12,
        timeout: cdk.Duration.minutes(15),
      }
    );
    bootstrapFunction.addToRolePolicy(
      new iam.PolicyStatement({
        resources: [this.ideRole.roleArn],
        actions: ["iam:PassRole"],
      })
    );
    bootstrapFunction.addToRolePolicy(
      new iam.PolicyStatement({
        resources: ["*"],
        actions: [
          "ec2:DescribeInstances",
          "iam:ListInstanceProfiles",
          "ssm:DescribeInstanceInformation",
          "ssm:SendCommand",
          "ssm:GetCommandInvocation",
        ],
      })
    );

    const logGroup = new logs.LogGroup(this, "IdeLogGroup", {
      retention: logs.RetentionDays.ONE_WEEK,
    });
    logGroup.grantWrite(this.ideRole);

    this.ideSecretsManagerPassword = new secretsmanager.Secret(
      this,
      "IdePasswordSecret",
      {
        generateSecretString: {
          excludePunctuation: true,
          passwordLength: 32,
          generateStringKey: "password",
          includeSpace: false,
          secretStringTemplate: JSON.stringify({ password: "" }),
          excludeCharacters: '"@/\\',
        },
      }
    );
    this.ideSecretsManagerPassword.grantRead(this.ideRole);

    const cfn_dist = new cfn.Distribution(this, "IdeDistribution", {
      defaultBehavior: {
        origin: new cfn_origins.HttpOrigin(
          this.ec2Instance.instancePublicDnsName,
          {
            protocolPolicy: cfn.OriginProtocolPolicy.HTTP_ONLY,
            httpPort: 80,
          }
        ),
        allowedMethods: {
          methods: ["GET", "HEAD", "OPTIONS", "PUT", "PATCH", "POST", "DELETE"],
        },
        cachePolicy: cfn.CachePolicy.CACHING_DISABLED,
        originRequestPolicy: cfn.OriginRequestPolicy.ALL_VIEWER,
        viewerProtocolPolicy: cfn.ViewerProtocolPolicy.ALLOW_ALL,
      },
      httpVersion: cfn.HttpVersion.HTTP2,
    });

    const cfnWaitConditionHandle = new cdk.CfnWaitConditionHandle(
      this,
      "IdeBootstrapWaitConditionHandle",
      {}
    );

    const cfnWaitCondition = new cdk.CfnWaitCondition(
      this,
      "IdeBootstrapWaitCondition",
      {
        count: 1,
        handle: cfnWaitConditionHandle.ref,
        timeout: `${options.bootstrapTimeoutMinutes * 60}`,
      }
    );
    cfnWaitCondition.node.addDependency(this.ec2Instance);

    let installGitea = this.addGiteaToSSMTemplate(options);

    const ssmDocument = new ssm.CfnDocument(this, "IdeBootstrapDocument", {
      documentType: "Command",
      documentFormat: "YAML",
      updateMethod: "NewVersion",
      content: {
        schemaVersion: "2.2",
        description: "Bootstrap IDE",
        parameters: {
          BootstrapScript: {
            type: "String",
            description: "(Optional) Custom bootstrap script to run.",
            default: "",
          },
        },
        mainSteps: [
          {
            action: "aws:runShellScript",
            name: "IdeBootstrapFunction",
            inputs: {
              runCommand: [
                cdk.Fn.sub(ssmDocumentTemplate, {
                  instanceIamRoleName: this.ideRole.roleName,
                  instanceIamRoleArn: this.ideRole.roleArn,
                  passwordName: this.ideSecretsManagerPassword.secretName,
                  domain: cfn_dist.distributionDomainName,
                  codeServerVersion: options.codeServerVersion,
                  waitConditionHandleUrl: cfnWaitConditionHandle.ref,
                  customBootstrapScript: options.bootstrapScript,
                  installGitea,
                  splashUrl: options.splashUrl,
                  readmeUrl: options.readmeUrl,
                  environmentContentsZip: options.environmentContentsZip,
                  extensions: options.extensions.join(","),
                  terminalOnStartup: `${options.terminalOnStartup}`,
                }),
              ],
            },
          },
        ],
      },
    });
    cfnWaitCondition.node.addDependency(ssmDocument);

    const bootstrapResource = new cdk.CustomResource(
      this,
      "IdeBootstrapResource",
      {
        serviceToken: bootstrapFunction.functionArn,
        properties: {
          InstanceId: this.ec2Instance.instanceId,
          SsmDocument: ssmDocument.ref,
          LogGroupName: logGroup.logGroupName,
        },
      }
    );

    this.bootstrapped = bootstrapResource;

    this.accessUrl = "https://" + cfn_dist.distributionDomainName;

    if (options.exportIdePassword) {
      this.idePassword = this.getIdePassword();
    }
  }

  public getIdePassword(): string {
    if (!this.passwordResource) {
      const passwordFunction = new lambda.Function(
        this,
        "IdePasswordExporterFunction",
        {
          code: lambda.Code.fromInline(passwordLambda),
          handler: "index.lambda_handler",
          runtime: lambda.Runtime.PYTHON_3_12,
          timeout: cdk.Duration.minutes(3),
        }
      );
      this.ideSecretsManagerPassword.grantRead(passwordFunction);

      this.passwordResource = new cdk.CustomResource(
        this,
        "IdePasswordExporter",
        {
          serviceToken: passwordFunction.functionArn,
          properties: {
            PasswordName: this.ideSecretsManagerPassword.secretName,
          },
        }
      );
    }

    return this.passwordResource.getAttString("password");
  }

  private addGiteaToSSMTemplate(options: VSCodeIdeProps) {
    if (!options.enableGitea) return "";

    return GiteaVsCodeBootstrapScript;
  }
}
