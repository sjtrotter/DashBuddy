// SPIKE (#192) — stand-in for the future separate Apache-2.0 "matchers" repo.
// This is a plain-JVM Gradle build with NO Android deps, includeable into the
// app build via `includeBuild("matchers")` (guarded by -PuseLocalMatchers).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "matchers"
