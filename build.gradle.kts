plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

group = "io.zenwave360.manifest"
version = "0.9.1"

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
