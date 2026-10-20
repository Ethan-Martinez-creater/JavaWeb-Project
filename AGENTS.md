# Instructions for coding agents (Codex)

This repository uses stage-gated delivery. **Current task: Stage 0 environment preflight only. Do not start Stage 1 implementation until the owner/reviewer authorizes it after version review.** Read docs/06-environment-preflight.md now. Read these files before writing Stage 1 code:
1. docs/00-project-charter.md
2. docs/01-architecture-and-code-style.md
3. docs/02-domain-and-api-contract.md
4. docs/03-stage-roadmap-and-gates.md
5. docs/04-stage-1-codex-task.md (Stage 1 only)

## Stage 0 environment preflight exception (current)

- Follow docs/06-environment-preflight.md. This is a one-time, **read-only environment inventory**, not a code review or software installation task.
- Inspect only the environment Codex can actually access. Do not claim a remote Codex sandbox is the user's laptop/PC.
- Do not install, upgrade, pull Docker images, start/stop services, run build commands that download dependencies, alter PATH/JAVA_HOME, or create implementation code.
- Deliver a concise environment matrix and recommendation questions in a small PR from codex/environment-preflight into main, changing only docs/handoffs/environment-preflight.md. Do not merge. Stop and ask for the next instruction.
- If unable to push/PR, give the complete sanitized version matrix directly to the user with an honest limitation. Do not proceed with Stage 1.

## Source of truth / execution

- The documents in main specify product scope, contracts and coding rules. **Only after Stage 0 approval**, work on a feature branch named codex/stage-1-foundation for Stage 1, branching from main. Never push feature code directly to main.
- Complete **only** the authorized stage and all its acceptance criteria. Do not start Stage 2, introduce Redis/RabbitMQ, or create a Vue frontend during Stage 1.
- You are the implementer, not the reviewer. Do not merge PRs and do not declare a stage approved. Await human/ChatGPT stage-gate review before moving on.
- You may make normal engineering decisions without frequent questions; record impactful decisions as short ADRs under docs/decisions/. Do not silently change fixed technology/architecture, scope or security constraints.
- Use idiomatic Java 21, Spring Boot 4.1.1 and the MyBatis Starter compatible with Boot 4.1; confirm dependency coordinates/version availability during build. Use MyBatis, **not JPA/MyBatis-Plus**, for persistence. Avoid unnecessary abstractions.
- Prioritize readability for a developer later studying the code. Consistent feature-based packages, meaningful names, concise comments explaining WHY, and clear state transitions are mandatory. Never add dead code or fake success paths.
- All endpoints must enforce authorization AND resource ownership. Never permit self-service privilege escalation from USER to ORGANIZER/ADMIN.
- Use externalized secrets, robust validation, centralized exceptions, UTC timestamps and reproducible Flyway migrations. No hard-coded real credentials or tokens.
- All changed functionality requires tests. Run formatting and tests with the committed Maven Wrapper. Tests requiring MySQL should run against real MySQL using Testcontainers (do not substitute H2). If Docker/build infrastructure is unavailable, report exact commands, errors and unverified checks; never claim a test passed without execution.
- Do not make gratuitous library/version swaps to resolve one error. Consult official docs and document alternatives if necessary.
- Keep PR manageable by logical commits; create one stage PR with a reproducible test report and docs/handoffs/stage-1.md using docs/05-handoff-template.md. Include a completed checklist, risks, commands and real observed results.
- Stage delivery condition is **PR open, not merged**, and a succinct message containing PR URL and SHA.

## Stage changes

At each new stage, the reviewer will authorize updated stage instructions in this repository. Never infer authorization from a future plan alone.

## Never do

- Never claim benchmarks without raw test evidence.
- Never log passwords, tokens or personal information.
- Never bypass security in production profiles for demo convenience.
- Never share mutable persistence entities directly as public API contracts.
- Never couple unrelated modules by calling each other's MyBatis mappers.
