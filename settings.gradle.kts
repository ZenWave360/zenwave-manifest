pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "zenwave-manifest"

val localJsonRefParser = file("../json-schema-ref-parser-kmp")
if (localJsonRefParser.exists()) {
    includeBuild(localJsonRefParser) {
        dependencySubstitution {
            substitute(module("io.zenwave360.jsonrefparser:json-schema-ref-parser-kmp")).using(project(":"))
        }
    }
}

include("manifest-core")
