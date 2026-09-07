plugins {
    // Fetches a JDK 21 toolchain when the machine running the build does not have one
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "github-repos-api"
