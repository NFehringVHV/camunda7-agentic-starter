# Contributing to camunda7-agentic

Thanks for your interest in contributing! This project is licensed under the
[Apache License 2.0](LICENSE).

## Ground rules

- By submitting a contribution you agree that it is licensed under the Apache-2.0 license and that
  you have the right to submit it (see [Developer Certificate of Origin](https://developercertificate.org/)).
- Keep the core **provider-neutral**: the starter must not depend on any concrete LLM provider SDK.
  Provider-specific code belongs in the examples repository or in user applications.
- Every source file must carry an SPDX header: `SPDX-License-Identifier: Apache-2.0`.
- Write code, comments, commit messages, and documentation in **English**.

## Development

- Java 21+, Maven.
- Build and test with `mvn verify`. The build must stay green and fully offline-capable
  (no network/LLM provider required to compile or run the unit tests).
- Add or update unit tests for any behaviour change.

## Pull requests

- Branch from `main` and open a pull request against `main`.
- The `main` branch is protected: changes land via reviewed pull requests, not direct pushes.
- Keep PRs focused; describe the motivation and the change. Reference any related issue.

## Reporting bugs / requesting features

Open a GitHub issue with a clear description and, for bugs, a minimal reproduction (BPMN snippet,
configuration, stack trace).
