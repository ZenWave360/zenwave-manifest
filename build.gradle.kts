plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

group = "io.zenwave360.manifest"
version = "1.0.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
        mavenLocal()
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
