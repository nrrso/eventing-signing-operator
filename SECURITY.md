# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest  | Yes       |

## Reporting a Security Issue

If you discover a security issue in this project, please report it responsibly.

**Do not open a public GitHub issue for security problems.**

Instead, please use [GitHub's private vulnerability reporting](https://github.com/nrrso/eventing-signing-operator/security/advisories/new) to submit your report.

Include as much detail as possible:

- Description of the issue
- Steps to reproduce
- Affected versions
- Potential impact 

You should expect an initial response within 72 hours. I will work with you to understand the issue and coordinate a fix before any public disclosure.

## Scope

This project handles cryptographic key material (Ed25519 signing keys). Issues in the following areas are of particular interest:

- Private key exposure or leakage
- Signature forgery or bypass
- Key rotation failures
- Registry tampering

Please make sure you are familiar with the project and its architecture before disclosing a security issue. 

## Disclosure Policy

This repo follows coordinated disclosure. Once a fix is available, I will publish a security advisory and credit the reporter (unless they prefer to remain anonymous).
