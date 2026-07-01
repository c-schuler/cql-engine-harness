# cql-engine-harness

In-process harness for running the [CQL Tests](https://github.com/cqframework/cql-tests)
conformance suite directly against the reference CQL engine.

`cql-engine-harness` runs the cql-tests conformance suite against the reference CQL engine
([`clinical_quality_language`](https://github.com/cqframework/clinical_quality_language)) in a
single JVM. It wires the engine in via a **Gradle composite build**, so it compiles and evaluates
each test expression against live engine source — no publishing, no FHIR server, and no
clinical-reasoning or JPA-server stack in the loop. The result is a fast, debuggable loop for
finding and fixing engine and translator failures.

## How it works

For each `<test>` in the suite, the harness generates a tiny CQL library with the `<expression>`
and `<output>` as separate `define`s, compiles them with `cql-to-elm`, evaluates them with the
engine, and compares the two result `Value`s using the engine's own equivalence semantics. Tests
marked `invalid` are expected to fail compilation or evaluation. It's a report generator — it never
asserts on the failure count, so it won't fail the build.

The engine is consumed as **live source** through `includeBuild("../clinical_quality_language/Src/java")`
in `settings.gradle.kts`. Composite-build dependency substitution is version-agnostic (it matches by
`group:module`), so the `org.cqframework:*` dependencies resolve to that checkout — edits to the
engine are picked up on the next run, and you can set breakpoints straight into engine source.

## Prerequisites

- JDK 17
- `clinical_quality_language` checked out as a **sibling** directory (`../clinical_quality_language`).
- Git submodules initialized (the cql-tests suite is vendored as a submodule).

## Quick start

```sh
git clone --recurse-submodules https://github.com/c-schuler/cql-engine-harness.git
cd cql-engine-harness
./gradlew test
```

The run writes a report to `build/reports/cql-conformance.txt`:

```
=== cql-engine-harness conformance run ===
total=1823  pass=1747  fail=61  error=15  (95.8% pass)
--- per file (pass/total) ---
...
--- failures & errors ---
FAIL  [CqlDateTimeOperatorsTest.xml] Add/DateTimeAddYearInWeeks: ...
```

## Configuration

- **Test suite location** resolves in order: `CQL_TESTS_DIR` env var → `-DcqlTestsDir=...` system
  property → the vendored submodule `cql-tests/tests/cql`.
- **Pinning the suite:** the `cql-tests` submodule is pinned to a specific commit for reproducible
  numbers. Bump it with `git -C cql-tests checkout <ref>` and commit the gitlink.

## Debugging a single failure

Open the project, set a breakpoint in the engine (it's source via the composite build), and run the
`CqlConformanceHarness` test — or narrow the run by pointing `CQL_TESTS_DIR` at a directory holding
just the test file you care about.

## Roadmap

- **Layer 1 (current):** engine + translator semantics, comparing result `Value`s directly.
- **Layer 2 (planned):** add an optional serialization stage (engine-fhir `FhirTypeConverter` plus a
  port of clinical-reasoning's `$cql` result→`Parameters` assembly) to reproduce result-shape issues
  end-to-end, and optionally expose a minimal `$cql` HTTP endpoint so the official
  [cql-tests-runner](https://github.com/cqframework/cql-tests-runner) can drive this engine without
  the clinical-reasoning / JPA-server stack.
