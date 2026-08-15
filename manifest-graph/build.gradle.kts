import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
    alias(libs.plugins.kotlinx.kover)
}

tasks.withType<Test>().configureEach {
    System.getProperty("arcadia.architecture")?.let {
        systemProperty("arcadia.architecture", it)
    }
}

kotlin {
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    js(IR) {
        nodejs()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":manifest-core"))
            implementation(libs.dsl.kotlin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.json.schema.ref.parser.kmp)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(kotlin("stdlib-jdk8"))
        }
    }
}

val hasSigningCredentials = sequenceOf(
    "signingInMemoryKey",
    "signingKey",
    "signing.secretKeyRingFile",
).any { !providers.gradleProperty(it).orNull.isNullOrBlank() }

mavenPublishing {
    publishToMavenCentral()
    if (hasSigningCredentials) {
        signAllPublications()
    }
    pom {
        name.set("ZenWave Manifest Graph")
        description.set("Semantic architecture graph built from ZenWave manifests and ZDL/ZFL artifacts")
        url.set("https://github.com/ZenWave360/zenwave-manifest")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("ivangsa")
                name.set("Ivan Garcia Sainz-Aja")
                email.set("ivangsa@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/ZenWave360/zenwave-manifest.git")
            developerConnection.set("scm:git:ssh://github.com/ZenWave360/zenwave-manifest.git")
            url.set("https://github.com/ZenWave360/zenwave-manifest")
        }
    }
}
