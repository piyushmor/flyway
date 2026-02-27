# R2DBC Implementation Planning Documents

**Project**: Add R2DBC (Reactive Relational Database Connectivity) support to Flyway

**Status**: Phase 1 ✅ Complete | Phase 2 📋 Ready | Phase 3 🔄 Planned | Phase 4 📚 Planned

**Last Updated**: February 27, 2026

---

## Quick Start

### Where to Begin?

**1. Executive Summary** → `IMPLEMENTATION_SUMMARY.md`
- Overview of what has been accomplished
- Critical guarantees and constraints
- Risk assessment
- Next steps for all phases

**2. Architecture Overview** → `R2DBC_IMPLEMENTATION_STRATEGY.md`
- 4-phase roadmap
- Core design principles
- Module structure
- Success criteria

**3. Backward Compatibility** → `BACKWARD_COMPATIBILITY_STRATEGY.md`
- Critical guardrails
- What NOT to do
- Testing strategy
- Phase-by-phase validation

**4. Phase 1 Verification** → `PHASE_1_VERIFICATION.md`
- Code changes analysis
- Verification results
- Risk assessment
- Sign-off documentation

**5. Spring Boot Integration** → `SPRING_BOOT_INTEGRATION_POINTS.md`
- Repository split responsibilities
- Phase 3 requirements
- Detection logic

**6. Phase 3 Implementation** → `PHASE_3_SPRING_BOOT_CHANGES.md`
- Step-by-step guide for Spring Boot repo changes
- Code examples
- Bean initialization order
- Testing strategy

---

## Repository Structure

```
.planning/
├── README.md (this file)
├── IMPLEMENTATION_SUMMARY.md ← START HERE
├── R2DBC_IMPLEMENTATION_STRATEGY.md
├── BACKWARD_COMPATIBILITY_STRATEGY.md
├── PHASE_1_VERIFICATION.md
├── SPRING_BOOT_INTEGRATION_POINTS.md
├── PHASE_3_SPRING_BOOT_CHANGES.md
└── codebase/
    ├── STACK.md (technology stack)
    ├── ARCHITECTURE.md (design patterns)
    ├── STRUCTURE.md (module organization)
    ├── INTEGRATIONS.md (external dependencies)
    ├── CONVENTIONS.md (coding standards)
    ├── TESTING.md (test patterns)
    └── CONCERNS.md (technical risks)
```

---

## Phase Overview

### Phase 1: ✅ COMPLETE

**Status**: Core infrastructure implemented and verified

**Deliverables**:
- NativeConnectorsR2dbc abstract base class
- R2dbcExecutor blocking utility
- ConnectionType.R2DBC enum value
- flyway-nc/flyway-r2dbc-core module

**Guarantees**:
- Zero modifications to JDBC code
- Zero breaking changes to public APIs
- 100% backward compatibility maintained
- Database-agnostic core design

**Commits**:
- 04364f642 - Codebase mapping & planning
- 670930c1a - Core infrastructure
- b49871376 - Backward compatibility strategy
- 4c43fe2b4 - Phase 1 verification
- 4adda3665 - Spring Boot integration points
- 4010c2ac5 - Phase 3 documentation update
- f55645732 - Implementation summary
- b6d0093c8 - Phase 3 implementation guide

### Phase 2: 📋 PLANNED

**Scope**: Database-specific R2DBC adapters (this repository)

**Deliverables**:
- flyway-database-nc-r2dbc-postgresql (reference implementation)
- flyway-database-nc-r2dbc-mysql
- flyway-database-nc-r2dbc-h2 (for testing)

**Pattern**: Extend NativeConnectorsR2dbc, reuse existing parsers

**Timeline**: Ready to start immediately after Phase 1 approval

### Phase 3: 🔄 PLANNED

**Scope**: Spring Boot auto-configuration (separate repository)

**Repository**: `https://github.com/spring-projects/spring-boot`

**Location**: `spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/`

**Local Setup**: Cloned at `/Users/pmor/IdeaProjects/OSS/spring-boot`

**Deliverables**:
- FlywayR2dbcAutoConfiguration (new)
- FlywayProperties updates
- R2dbcConnectionDetector
- Starter dependency updates
- Comprehensive tests

**Key Principle**: All changes additive and backward compatible

**Detailed Guide**: See `PHASE_3_SPRING_BOOT_CHANGES.md`

### Phase 4: 📚 PLANNED

**Scope**: Testing, documentation, and release (both repositories)

**Deliverables**:
- E2E tests with Testcontainers
- Migration compatibility tests
- User migration guides (JDBC → R2DBC)
- Release notes and examples

---

## Local Repository Setup

Both repositories are cloned locally:

```
/Users/pmor/IdeaProjects/OSS/
├── flyway/              ← Flyway OSS (Phase 1-2, 4 work)
│   ├── .planning/       ← This directory
│   ├── flyway-core/
│   ├── flyway-nc/
│   │   └── flyway-r2dbc-core/     ← Phase 1 deliverable
│   └── flyway-database/
│
└── spring-boot/         ← Spring Boot (Phase 3 work)
    └── spring-boot-project/
        └── spring-boot-starters/
            └── spring-boot-starter-flyway/
                ├── pom.xml
                └── src/main/java/org/springframework/boot/autoconfigure/flyway/
```

---

## Key Concepts

### JDBC & R2DBC Parity

Both connection types must be **first-class citizens**:
- ✅ Identical schema history table structure
- ✅ Same SQL migration scripts work on both
- ✅ Seamless switching without re-running migrations
- ✅ JDBC is default (backward compatible)

### Database-Agnostic Design

Core R2DBC module contains:
- ✅ No PostgreSQL-specific code
- ✅ No MySQL-specific code
- ✅ No database-specific logic

Database implementations extend abstract base:
- Database-specific adapters in Phase 2
- Reuse existing SQL parsers (no duplication)

### Blocking Strategy

Challenge: Flyway is synchronous; R2DBC is reactive

Solution: `Mono.block()` with configurable timeout
```java
Mono.from(connectionFactory.create())
    .block(Duration.ofMinutes(5));
```

### Connection Type Precedence

When both JDBC and R2DBC are configured:

1. **Explicit property** (highest priority)
   - `spring.flyway.connection-type=r2dbc` or `jdbc`

2. **Auto-detection**
   - Only JDBC available → use JDBC
   - Only R2DBC available → use R2DBC
   - Both available → use JDBC (default)

3. **Error** (if neither available)
   - Fail with clear error message

---

## Critical Requirements

All these MUST be maintained throughout implementation:

- [x] JDBC and R2DBC treated equally (Phases 1-2)
- [x] JDBC remains default (Phases 1-3)
- [x] R2DBC is database-agnostic (Phase 1-2)
- [x] Schema history identical (Phases 1-2)
- [x] Seamless JDBC↔R2DBC switching (Phases 1-3)
- [x] Zero breaking changes (All phases)
- [x] All existing JDBC code untouched (All phases)
- [x] Full backward compatibility (All phases)

---

## Risk Assessment

**Phase 1**: ✅ MINIMAL (only new files + additive enum)
**Phase 2**: ✅ LOW (extending abstract base, reusing patterns)
**Phase 3**: ✅ MEDIUM (coordinated with Spring Boot, careful about bean ordering)
**Phase 4**: ✅ LOW (testing and documentation)

All identified risks have documented mitigation strategies.

---

## Next Steps

### Immediate (Phase 2)

1. Implement PostgreSQL R2DBC adapter (reference implementation)
2. Implement MySQL R2DBC adapter
3. Implement H2 R2DBC adapter
4. Add integration tests
5. Verify seamless JDBC↔R2DBC switching

**Estimated Effort**: Low-Medium
**Time Frame**: Can start immediately

### Short Term (Phase 3)

1. Coordinate with Spring Boot maintainers
2. Implement Spring Boot auto-configuration changes
3. Add FlywayR2dbcAutoConfiguration
4. Update FlywayProperties
5. Implement detection logic
6. Add comprehensive tests

**Repository**: Spring Boot repo (`/Users/pmor/IdeaProjects/OSS/spring-boot`)
**Guide**: See `PHASE_3_SPRING_BOOT_CHANGES.md`
**Estimated Effort**: Medium
**Time Frame**: After Phase 2 complete

### Long Term (Phase 4)

1. E2E tests with Testcontainers
2. Migration compatibility tests
3. User migration guides
4. Release notes and examples
5. Final verification and sign-off

**Estimated Effort**: Medium
**Time Frame**: After Phase 3 complete

---

## Documentation Principles

All documents follow these principles:

✅ **Clarity**: Clear, concise, easy to understand
✅ **Completeness**: All relevant details included
✅ **Consistency**: Same terminology and conventions
✅ **Searchability**: Easy to find information
✅ **Action-Oriented**: Include concrete next steps

---

## Questions?

Refer to the specific phase document:

- **"What's done?"** → `IMPLEMENTATION_SUMMARY.md`
- **"How does it work?"** → `R2DBC_IMPLEMENTATION_STRATEGY.md`
- **"Will it break my code?"** → `BACKWARD_COMPATIBILITY_STRATEGY.md`
- **"Is it safe?"** → `PHASE_1_VERIFICATION.md`
- **"How do Spring Boot and Flyway interact?"** → `SPRING_BOOT_INTEGRATION_POINTS.md`
- **"What do I do in Spring Boot repo?"** → `PHASE_3_SPRING_BOOT_CHANGES.md`

---

## Version History

| Version | Date | Notes |
|---------|------|-------|
| 1.0 | 2026-02-27 | Phase 1 complete, planning docs created |

---

*Last updated: 2026-02-27 | All documents are in this `.planning/` directory*
