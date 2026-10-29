# 0.9.0

Release date: October 29, 2026.

## What Changed?

Opt-in versioned L1 reads and owned JSON responses reduce repeated Redis reads and avoid Java payload arrays on supported paths. Snapshot fencing, projection locks and reader/writer separation remain intact. Native extraction uses a bounded buffer under the configured home directory.

## How To Use It

1. Align your dependency versions with the release set below.
2. Rebuild the application. Use the native file packaged with the selected JAR; do not mix older DLL/SO files.
3. Choose the feature you need in the [usage guide](../docs/versioned-l1.md). Optional features are not enabled automatically.
4. Validate under your own CPU, memory and traffic budgets. Do not blindly retry a timed-out write.

## Aligned Versions

REST/platform: 4.8.0; cache: 0.9.0; Dubbo: 0.9.0; Spring Dubbo: 0.8.0; agent: 0.7.1.
Sample applications: 0.8.0; shared model/interfaces: 0.5.0.
Native contracts are unchanged: REST 29, Dubbo 7, Spring Dubbo 3, Redis 6, Glowroot 6.

## Qualification Scope

Java business logic, reflection-free dispatch and bounded resources are preserved.
L1 is optional; a net memory reduction is not guaranteed for every workload.
Consumer warmup rejections remain a separate improvement item.
A fixed 2.695 ms writer p99 and zero consumer warmup rejections are not general product guarantees.
Measured correctness and error checks remain applicable. No new all-platform performance guarantee is claimed.

For GitHub Packages, match the Maven settings.xml server ID to the repository ID.
Use a token with read:packages and, when required, private repository access. SSH authenticates Git only.
