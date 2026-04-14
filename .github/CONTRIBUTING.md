# Contributing to CloudEvent Signing Operator

Thank you for your interest in contributing! This document covers the process for contributing to this project.

## Developer Certificate of Origin (DCO)

This project uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO). All commit messages must contain a `Signed-off-by` line to indicate that the contributor agrees to the DCO.

### How to sign off

Add the `-s` / `--signoff` flag when you commit:

```bash
git commit -s -m "feat: add new feature"
```

This appends a `Signed-off-by` line with your name and email to the commit message:

```
feat: add new feature

Signed-off-by: Your Name <your.email@example.com>
```

The sign-off must match the commit author. The [DCO GitHub App](https://github.com/apps/dco) checks every pull request and will flag any unsigned commits.

### Fixing unsigned commits

If you forgot to sign off, amend or rebase to add it:

```bash
# Amend the most recent commit
git commit --amend --signoff

# Sign off the last N commits
git rebase HEAD~N --signoff
```

Then force-push your branch:

```bash
git push --force-with-lease
```

### Make sign-off automatic locally (optional)

```bash
git config format.signOff true
```

## Getting Started

1. Fork the repository and clone your fork
2. Install tool versions with [mise](https://mise.jdx.dev/walkthrough.html): `mise install`
3. Build the project: `mvn clean package`
4. Run tests: `mvn test`

Check the ../Makefile for helpful dev commands.

## Making Changes

1. Create a branch from `main` with a descriptive name
2. Make your changes, ensuring all commits are signed off
3. Run tests locally before pushing: `mvn verify`
4. Run the linter: `mvn spotless:check` (fix with `mvn spotless:apply`)
5. Open a pull request against `main`

## Pull Requests

- Link the relevant GitHub issue in the PR description
- Keep PRs focused — one logical change per PR
- Add tests for new functionality
- Ensure CI checks pass before requesting review

## Reporting Issues

- **Bugs:** Use the [bug report template](https://github.com/nrrso/eventing-signing-operator/issues/new?template=bug.md)
- **Features:** Use the [feature request template](https://github.com/nrrso/eventing-signing-operator/issues/new?template=feature-request.md)

## Code of Conduct

Be respectful and constructive. Let's commit to providing a welcoming and inclusive experience for everyone.
