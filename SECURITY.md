# Security policy

ViscoSuite is infrastructure for healthcare data. We treat security reports with
priority and appreciate coordinated disclosure.

## Reporting a vulnerability

**Do not open a public issue for security problems.**

Email **security@viscosiety.com** with a description of the issue, the affected
component and version, and reproduction steps if you have them. You will receive
an acknowledgement within **3 business days** and a status update at least every
two weeks until resolution. We will credit you in the release notes unless you
prefer otherwise.

## Scope

- The ViscoSuite components in this repository: ViscoLink, ViscoStore
  configuration, ViscoRunner images and the demo configurations.
- Vulnerabilities in the underlying Frank!Framework should go to the
  [Frank!Framework project](https://github.com/frankframework/frankframework/security);
  vulnerabilities in HAPI FHIR to the HAPI FHIR project. If you are unsure where
  a problem belongs, report it to us and we will route it.

## Supported versions

Security fixes land on the latest minor release line. Older tags do not receive
backports; deployments are expected to track releases.

## Deployment notes

The demo overlay (`docker-compose.demo.yml`) is intentionally open (demo
credentials, generated traffic, no TLS) and is **not** a production
configuration. Production guidance — credentials via the credential factory,
TLS termination, network segmentation — is part of the deployment
documentation, and misconfigurations of your own deployment are outside the
scope of this policy.
