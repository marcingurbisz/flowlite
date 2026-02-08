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
            setSrcDirs(listOf("test"))
        }
        resources {
            setSrcDirs(listOf("test"))
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

    // Testing
    testImplementation(platform("io.kotest:kotest-bom:6.1.3"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm")
    testImplementation("io.kotest:kotest-assertions-core-jvm")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.2")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc:4.0.2")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc:4.0.2")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("com.github.kagkarlsson:db-scheduler:16.7.0")
}

tasks.test {
    useJUnitPlatform()
    
    testLogging {
        events("failed")
        showStandardStreams = false
    }

    finalizedBy(tasks.jacocoTestReport)
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
        property("sonar.sources", "source")
        property("sonar.tests", "test")
        property("sonar.java.binaries", "build/classes/kotlin/main")
        property("sonar.junit.reportPaths", "build/test-results/test")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
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
    mainClass.set("io.flowlite.test.ReadmeUpdaterKt")
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
                url.set("https://github.com/yourusername/flowlite")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("developer")
                        name.set("Developer Name")
                        email.set("developer@example.com")
                    }
                }
            }
        }
    }
}