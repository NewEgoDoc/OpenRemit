plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    kotlin("plugin.jpa") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.openremit"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "io.spring.dependency-management")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-Xannotation-default-target=param-property",
            )
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }

    dependencies {
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "implementation"("tools.jackson.module:jackson-module-kotlin")

        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// IntelliJ Gradle delegate가 :classes / :testClasses를 루트에서 호출할 때 대비한 aggregator
tasks.register("classes") {
    dependsOn(subprojects.map { "${it.path}:classes" })
}
tasks.register("testClasses") {
    dependsOn(subprojects.map { "${it.path}:testClasses" })
}
