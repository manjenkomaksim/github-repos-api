plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.manzhenko"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.1.1")
    // springdoc inspects the model through Jackson 2, and only this module tells it that a Kotlin
    // property is non-null, which is what marks a field as required in the generated document
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Netty resolves DNS through a native library and Spring Boot only pulls the x86_64 build of it,
    // which makes the JVM log an error and fall back on Apple Silicon
    runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Netty reaches for a native library, which Java 25 reports unless the access is declared and will
// refuse outright in a later release
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.bootJar {
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.bootRun {
    jvmArgs(nativeAccess)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(nativeAccess)
}
