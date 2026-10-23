# Instructions for coding agents (Codex)

This repository uses stage-gated delivery. **ALL FOUR IMPLEMENTATION STAGES ARE COMPLETE AND ACCEPTED** (Stage 1 PR #2, Stage 2 PR #3, Stage 3 PR #4, Stage 4 PR #5). **No new implementation stage is authorized.** Current repository state is maintenance and learning. Read docs/handoffs/final-acceptance.md; every original stage task is now historical. Preserve all tested business invariants. Do not autonomously start a new feature, rewrite the app, or merge PRs. Read these files before writing code:
1. docs/00-project-charter.md
2. docs/01-architecture-and-code-style.md
3. docs/02-domain-and-api-contract.md
4. docs/03-stage-roadmap-and-gates.md
5. docs/handoffs/final-acceptance.md (final stage approval and learning, current)
6. docs/10-stage-4-codex-task.md (historical Stage 4)
7. docs/09-stage-3-codex-task.md (historical Stage 3)
8. docs/08-stage-2-codex-task.md (historical Stage 2)
9. docs/04-stage-1-codex-task.md (historical Stage 1)

## Stage 0 environment preflight decisions (completed)

- Stage 0 was completed in PR #1 and reviewed. Preserve its report; this block is historical and must not delay Stage 1 once PR #1 has been merged.
- Reuse existing JDK 21.0.9 for project-local processes via temporary environment variables, Maven 3.8.6 (or compatible committed wrapper), Node 24. Do not globally change Java 17 defaults.
- MySQL 5.7 is an existing unrelated local service on 3306; do not alter it. Containerize MySQL 8.4 using localhost port 3307 for host tools, but Testcontainers should use its own ephemeral mapped ports.
- Stage 4 validated the installed Docker Linux engine, full Compose, real E2E and CI; the Stage 0 daemon diagnosis is historical. Never modify Windows/WSL or global Docker without explicit user approval.
- Redis and RabbitMQ are implemented as isolated project containers; do not install native instances or change unrelated services.

## Source of truth / execution

- The documents in main specify product scope, contracts and coding rules. **All four stages are merged and final ACCEPT is recorded; there is no authorized next stage.** For any future explicitly requested maintenance, first read docs/handoffs/final-acceptance.md and work on a fresh scoped branch + PR. Never push feature code directly to main.
- Do not restart Stage 4 or begin unspecified enhancement work. Only implement the specific future maintenance or learning request explicitly authorized by the user; preserve API/security and database invariants.
- You are the implementer, not the reviewer. Do not merge PRs and do not declare a stage approved. Await human/ChatGPT stage-gate review before moving on.
- You may make normal engineering decisions without frequent questions; record impactful decisions as short ADRs under docs/decisions/. Do not silently change fixed technology/architecture, scope or security constraints.
- Use idiomatic Java 21, Spring Boot 4.1.1 and the MyBatis Starter compatible with Boot 4.1; confirm dependency coordinates/version availability during build. Use MyBatis, **not JPA/MyBatis-Plus**, for persistence. Avoid unnecessary abstractions.
- Prioritize readability for a developer later studying the code. Consistent feature-based packages, meaningful names, concise comments explaining WHY, and clear state transitions are mandatory. Never add dead code or fake success paths.
- All endpoints must enforce authorization AND resource ownership. Never permit self-service privilege escalation from USER to ORGANIZER/ADMIN.
- Use externalized secrets, robust validation, centralized exceptions, UTC timestamps and reproducible Flyway migrations. No hard-coded real credentials or tokens.
- All changed functionality requires tests. Run formatting and tests with the committed Maven Wrapper. Tests requiring MySQL should run against real MySQL using Testcontainers (do not substitute H2). If Docker/build infrastructure is unavailable, report exact commands, errors and unverified checks; never claim a test passed without execution.
- Do not make gratuitous library/version swaps to resolve one error. Consult official docs and document alternatives if necessary.
- For any future specifically authorized maintenance PR, keep changes scoped, include test evidence and documentation if applicable, and do not merge it yourself. Historic Stage 1–4 handoffs are complete.
- Future maintenance delivery condition remains an open PR with URL/SHA and reproducible evidence, pending ChatGPT review.

## Stage changes

All original stages are complete. Future maintenance or feature work needs a new explicit user authorization; never infer it from old task books or roadmap.

## Never do

- Never claim benchmarks without raw test evidence.
- Never log passwords, tokens or personal information.
- Never bypass security in production profiles for demo convenience.
- Never share mutable persistence entities directly as public API contracts.
- Never couple unrelated modules by calling each other's MyBatis mappers.
