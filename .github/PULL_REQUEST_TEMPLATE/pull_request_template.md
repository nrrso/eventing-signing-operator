> Please ensure **"Allow edits from maintainers"** is checked on your PR.
> This lets us fix small issues directly instead of round-tripping review comments.

Closes {LINK TO GH ISSUE}


## Description

A clear and concise description of the PR.

Use this section for review hints, explanations or discussion points/todos.

- Summary of changes
- Reasoning
- Additional context

How to contribute: https://github.com/nrrso/eventing-signing-operator/blob/main/.github/CONTRIBUTING.md


## Logs (optional)

Logs of the changes associated with this PR.


## Docs

Add any notes that help to document the feature/changes. Doesn't need to be your best writing, just a few words and/or code snippets.


## Ready?

Did you do any of the following? If not, no worries, but if you can
it's really helpful.

- [ ] Documented what's new
- [ ] Added in-code documentation (wherever needed)
- [ ] Wrote tests for new components/features
- [ ] Ran the linter to ensure style guidelines were followed
- [ ] Created a demo

**Security and architecture (if applicable):**
- [ ] No changes to canonical form or signing paths without property-based tests
- [ ] No private key material in logs, status, or non-Secret resources
- [ ] Used JOSDK dependent-resource patterns (not manual `context.getClient()` calls)


## Developer Certificate of Origin

By submitting this pull request, I confirm that all commits are signed off in accordance with the [Developer Certificate of Origin (DCO)](https://developercertificate.org/). See the [Contributing Guide](../CONTRIBUTING.md) for details.