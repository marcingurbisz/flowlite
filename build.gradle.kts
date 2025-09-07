plugins {
    kotlin("jvm") version "2.2.10"
    `java-library`
    `maven-publish`
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

    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-framework-datatest:5.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
}

tasks.test {
    useJUnitPlatform()
    
    // Show test output in console
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.named<Jar>("sourcesJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

kotlin {
    jvmToolchain(21)
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