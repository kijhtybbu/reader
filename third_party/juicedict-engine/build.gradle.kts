// Vendored juicedict engine (GPL-3.0) as a pure-JVM module: no Android dependencies, so
// the upstream unit tests run on a plain JDK. See PROVENANCE.md for source and commit.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
