plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
    jacoco
    id("org.sonarqube") version "7.2.2.6593"
}

group = "io.flowlite"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val npmCommand = if (isWindows) "npm.cmd" else "npm"
val cockpitUiDir = layout.projectDirectory.dir("cockpit-ui")
val generatedTestAppResourcesDir = layout.buildDirectory.dir("generated/test-app-resources")
val generatedCockpitUiDistDir = generatedTestAppResourcesDir.map { it.dir("cockpit-ui/dist") }
val testAppRuntimeLibsDir = layout.buildDirectory.dir("test-app-libs")
val frontendCoverageReportDir = layout.buildDirectory.dir("reports/playwright/frontend-coverage")
val frontendCoverageRawDir = layout.buildDirectory.dir("reports/playwright/frontend-coverage-raw")
val frontendCoverageEnabled = providers.gradleProperty("frontendCoverage")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val usePrebuiltCockpitUi = providers.gradleProperty("usePrebuiltCockpitUi")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

// Configure custom source sets for the flat structure
sourceSets {
    main {
        kotlin {
            setSrcDirs(listOf("source"))
        }
        resources {
            setSrcDirs(listOf("source"))
        }
    }
    test {
        kotlin {
            setSrcDirs(listOf("test", "tools"))
        }
        resources {
            setSrcDirs(listOf("test"))
            srcDir(generatedTestAppResourcesDir)
        }
    }
}

dependencies {
    // Kotlin standard library
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.13")

    // Client-facing Spring Data JDBC implementations (optional; consumers must provide Spring deps)
    compileOnly("org.springframework.data:spring-data-jdbc:4.0.2")
    compileOnly("org.springframework.boot:spring-boot:4.0.2")
    compileOnly("org.springframework:spring-context:7.0.3")
    compileOnly("org.springframework:spring-tx:7.0.3")
    compileOnly("org.springframework:spring-webmvc:7.0.3")

    // Testing
    testImplementation(platform("io.kotest:kotest-bom:6.1.3"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm")
    testImplementation("io.kotest:kotest-assertions-core-jvm")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.3")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc:4.0.3")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc:4.0.3")
    testImplementation("org.springframework.boot:spring-boot-starter-web:4.0.3")
    testImplementation("com.microsoft.playwright:playwright:1.58.0")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("com.github.kagkarlsson:db-scheduler:16.7.0")
}

val installCockpitUiDeps by tasks.registering(Exec::class) {
    group = "build"
    description = "Install Cockpit UI dependencies."
    workingDir = cockpitUiDir.asFile
    commandLine(npmCommand, "ci")
    onlyIf { !usePrebuiltCockpitUi.get() }

    inputs.files(
        cockpitUiDir.file("package.json"),
        cockpitUiDir.file("package-lock.json"),
    )
    outputs.dir(cockpitUiDir.dir("node_modules"))
}

val buildCockpitUi by tasks.registering(Exec::class) {
    group = "build"
    description = "Build Cockpit UI static assets for tests."
    dependsOn(installCockpitUiDeps)
    workingDir = cockpitUiDir.asFile
    commandLine(npmCommand, "run", "build")
    if (frontendCoverageEnabled.get()) {
        environment("VITE_COVERAGE", "true")
    }
    onlyIf { !usePrebuiltCockpitUi.get() }

    inputs.files(
        cockpitUiDir.file("package.json"),
        cockpitUiDir.file("package-lock.json"),
        cockpitUiDir.file("index.html"),
        cockpitUiDir.file("vite.config.ts"),
        fileTree(cockpitUiDir.dir("src")) { include("**/*") },
    )
    inputs.property("frontendCoverageEnabled", frontendCoverageEnabled)
    outputs.dir(cockpitUiDir.dir("dist"))
}

val generateCockpitFrontendCoverage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Merge raw cockpit frontend coverage snapshots into HTML and LCOV reports."
    workingDir = cockpitUiDir.asFile
    commandLine(
        "node",
        "tools/generateFrontendCoverageReport.mjs",
        frontendCoverageRawDir.get().asFile.absolutePath,
        frontendCoverageReportDir.get().asFile.absolutePath,
    )
    onlyIf { frontendCoverageRawDir.get().asFile.exists() }

    inputs.dir(frontendCoverageRawDir)
    outputs.dir(frontendCoverageReportDir)
}

val syncCockpitUiDist by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy built Cockpit UI assets into the test-app classpath resources."
    dependsOn(buildCockpitUi)
    from(cockpitUiDir.dir("dist"))
    into(generatedCockpitUiDistDir)

    doFirst {
        val distIndex = cockpitUiDir.file("dist/index.html").asFile
        require(distIndex.exists()) {
            "Cockpit UI dist not found at ${distIndex.path}. Build it locally or provide a prebuilt dist when using -PusePrebuiltCockpitUi=true."
        }
    }

    inputs.dir(cockpitUiDir.dir("dist"))
    outputs.dir(generatedCockpitUiDistDir)
}

tasks.named("processTestResources") {
    dependsOn(syncCockpitUiDist)
}

tasks.test {
    dependsOn(syncCockpitUiDist)
    useJUnitPlatform()

    doFirst {
        delete(frontendCoverageReportDir)
        delete(frontendCoverageRawDir)
    }
    
    testLogging {
        events("failed")
        showStandardStreams = false
    }

    finalizedBy(tasks.jacocoTestReport)
    finalizedBy(generateCockpitFrontendCoverage)
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

sonar {
    properties {
        property("sonar.projectKey", "marcingurbisz_flowlite")
        property("sonar.organization", "marcingurbisz")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sources", "source,cockpit-ui/src")
        property("sonar.tests", "test,cockpit-ui/tests")
        property("sonar.java.binaries", "build/classes/kotlin/main")
        property("sonar.junit.reportPaths", "build/test-results/test")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.javascript.lcov.reportPaths", "build/reports/playwright/frontend-coverage/lcov.info")
    }
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.named<Jar>("sourcesJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.register<JavaExec>("updateReadme") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.flowlite.tools.ReadmeUpdaterKt")
}

tasks.register<JavaExec>("runTestApp") {
    group = "application"
    description = "Run the FlowLite test application with Cockpit routes on Tomcat."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.flowlite.test.TestApplicationMainKt")
}

tasks.register<JavaExec>("runPerfTestApp") {
    group = "application"
    description = "Run the FlowLite test application with a large pre-seeded showcase dataset for local performance testing."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.flowlite.test.TestApplicationMainKt")
    args(
        "--flowlite.showcase.initial-seed-count=600",
        "--flowlite.showcase.repeat-seeding-enabled=false",
        "--flowlite.showcase.max-action-delay-ms=0",
        "--flowlite.showcase.action-failure-rate=0",
        "--flowlite.showcase.max-event-delay-ms=0",
    )
}

tasks.register<Jar>("testAppJar") {
    group = "application"
    description = "Build the application jar for the FlowLite test app."
    dependsOn(tasks.named("testClasses"))
    archiveClassifier.set("test-app")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "io.flowlite.test.TestApplicationMainKt"
    }

    from(sourceSets["main"].output)
    from(sourceSets["test"].output)
}

val syncTestAppRuntimeLibs by tasks.registering(Sync::class) {
    group = "application"
    description = "Collect runtime dependency jars for the packaged FlowLite test app."
    dependsOn(tasks.named("testClasses"))
    from(
        sourceSets["test"].runtimeClasspath
            .filter { it.exists() && it.name.endsWith(".jar") },
    )
    into(testAppRuntimeLibsDir)
}

tasks.register("testAppBundle") {
    group = "application"
    description = "Build the packaged FlowLite test app jar and runtime libs for container deployment."
    dependsOn(tasks.named("testAppJar"), syncTestAppRuntimeLibs)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            
            from(components["java"])
            
            pom {
                name.set("FlowLite")
                description.set("A lightweight, developer-friendly workflow engine for Kotlin")
                url.set("https://github.com/marcingurbisz/flowlite")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/marcingurbisz/flowlite")
                    connection.set("scm:git:https://github.com/marcingurbisz/flowlite.git")
                    developerConnection.set("scm:git:ssh://git@github.com/marcingurbisz/flowlite.git")
                }
                developers {
                    developer {
                        id.set("marcingurbisz")
                        name.set("Marcin Gurbisz")
                    }
                }
            }
        }
    }
}