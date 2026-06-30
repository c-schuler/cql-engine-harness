plugins { kotlin("jvm") version "2.3.10" }

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // Resolved from the included build (../clinical_quality_language/Src/java) via composite
    // substitution. The version is a placeholder; coordinates are what matter.
    implementation("org.cqframework:engine:5.0.0-SNAPSHOT")
    implementation("org.cqframework:cql-to-elm:5.0.0-SNAPSHOT")
    // Quantity/UCUM operations (unit comparison & conversion) need the UCUM service at runtime.
    implementation("org.cqframework:ucum:5.0.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The engine logs via kotlin-logging over slf4j; provide a backend at runtime.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    // The harness writes a report and never asserts a failure count, so it won't fail the build.
    testLogging { showStandardStreams = true }
}
