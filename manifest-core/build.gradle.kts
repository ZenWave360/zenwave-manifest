import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
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
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation(libs.json.schema.ref.parser.kmp)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
            }
        }
    }
}

val hasSigningCredentials = sequenceOf(
    "signingInMemoryKey",
    "signingKey",
    "signing.secretKeyRingFile"
).any { !providers.gradleProperty(it).orNull.isNullOrBlank() }

mavenPublishing {
    publishToMavenCentral()
    if (hasSigningCredentials) {
        signAllPublications()
    }
    pom {
        name.set("ZenWave Manifest Core")
        description.set("Shared manifest parsing and loading library for ZenWave workspace tooling")
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
