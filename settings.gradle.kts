rootProject.name = "cql-engine-harness"

// Pull the reference CQL engine in as live source via a Gradle composite build.
// Dependency substitution is by coordinates (group:module), version-agnostic, so any
// dependency below on `org.cqframework:*` resolves to this included build — no
// publishing, no version bumps, and nothing changes in the CQL repo.
includeBuild("../clinical_quality_language/Src/java")
