# Security Policy

## Supported versions

This project is in an early `0.x` phase. Security fixes are applied to the latest released version
only.

## Reporting a vulnerability

Please **do not** open a public issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting for this repository
("Security" tab → "Report a vulnerability"). Include:

- a description of the vulnerability and its impact,
- steps to reproduce or a proof of concept,
- affected version(s) and configuration.

We will acknowledge your report, investigate, and coordinate a fix and disclosure timeline with you.

## Scope notes

- The starter is provider-neutral and never handles model credentials itself; credentials are
  managed by the Spring AI provider starter you add to your application. Never commit credentials.
- The conversation history may contain sensitive prompt/tool data. Choose a history store
  (`inline`, `camunda-bytearray`, `external`) appropriate to your data-protection requirements and
  secure the underlying storage (engine database or external blob store) accordingly.
