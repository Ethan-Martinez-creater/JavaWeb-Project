# Instructions for coding agents (Codex)

This repository uses stage-gated delivery. **Stage 0 PR #1, Stage 1 PR #2 and Stage 2 PR #3 were ACCEPTED and merged. Stage 3 is now authorized.** Start from latest main including PR #3. Read docs/09-stage-3-codex-task.md. Previous stage instructions are historical and all existing invariants must remain intact. Read these files before writing code:
1. docs/00-project-charter.md
2. docs/01-architecture-and-code-style.md
3. docs/02-domain-and-api-contract.md
4. docs/03-stage-roadmap-and-gates.md
5. docs/09-stage-3-codex-task.md (Stage 3, current)
6. docs/08-stage-2-codex-task.md (historical Stage 2)
7. docs/04-stage-1-codex-task.md (historical Stage 1)

## Stage 0 environment preflight decisions (completed)

- Stage 0 was completed in PR #1 and reviewed. Preserve its report; this block is historical and must not delay Stage 1 once PR #1 has been merged.
- Reuse existing JDK 21.0.9 for project-local processes via temporary environment variables, Maven 3.8.6 (or compatible committed wrapper), Node 24. Do not globally change Java 17 defaults.
- MySQL 5.7 is an existing unrelated local service on 3306; do not alter it. Containerize MySQL 8.4 using localhost port 3307 for host tools, but Testcontainers should use its own ephemeral mapped ports.
- Docker engine is not yet connected. Diagnose safely per docs/07; CI Linux can validate tests when local Docker remains unavailable, but NEVER silently skip database integration tests. Installing/upgrading Docker, changing WSL/Windows features, or modifying OS settings requires separate user approval.
- Do not install native Redis or RabbitMQ; reserve both for Stage 3.

## Source of truth / execution

- The documents in main specify product scope, contracts and coding rules. **Now that Stage 2 PR #3 is merged**, work on branch codex/stage-3-redis-rabbitmq for Stage 3, branching from latest main. Never push feature code directly to main.
- Complete **only Stage 3** and all its acceptance criteria. Introduce Redis/RabbitMQ solely for the specified Stage 3 use cases; do not start Stage 4 or create a Vue frontend.
- You are the implementer, not the reviewer. Do not merge PRs and do not declare a stage approved. Await human/ChatGPT stage-gate review before moving on.
- You may make normal engineering decisions without frequent questions; record impactful decisions as short ADRs under docs/decisions/. Do not silently change fixed technology/architecture, scope or security constraints.
- Use idiomatic Java 21, Spring Boot 4.1.1 and the MyBatis Starter compatible with Boot 4.1; confirm dependency coordinates/version availability during build. Use MyBatis, **not JPA/MyBatis-Plus**, for persistence. Avoid unnecessary abstractions.
- Prioritize readability for a developer later studying the code. Consistent feature-based packages, meaningful names, concise comments explaining WHY, and clear state transitions are mandatory. Never add dead code or fake success paths.
- All endpoints must enforce authorization AND resource ownership. Never permit self-service privilege escalation from USER to ORGANIZER/ADMIN.
- Use externalized secrets, robust validation, centralized exceptions, UTC timestamps and reproducible Flyway migrations. No hard-coded real credentials or tokens.
- All changed functionality requires tests. Run formatting and tests with the committed Maven Wrapper. Tests requiring MySQL should run against real MySQL using Testcontainers (do not substitute H2). If Docker/build infrastructure is unavailable, report exact commands, errors and unverified checks; never claim a test passed without execution.
- Do not make gratuitous library/version swaps to resolve one error. Consult official docs and document alternatives if necessary.
- Keep PR manageable by logical commits; create one stage PR with reproducible results and docs/handoffs/stage-3.md using docs/05-handoff-template.md. Include a completed checklist, risks, commands and real observed results.
- Stage delivery condition is **PR open, not merged**, and a succinct message containing PR URL and SHA.

## Stage changes

At each new stage, the reviewer will authorize updated stage instructions in this repository. Never infer authorization from a future plan alone.

## Never do

- Never claim benchmarks without raw test evidence.
- Never log passwords, tokens or personal information.
- Never bypass security in production profiles for demo convenience.
- Never share mutable persistence entities directly as public API contracts.
- Never couple unrelated modules by calling each other's MyBatis mappers.
