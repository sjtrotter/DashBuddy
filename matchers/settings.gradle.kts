// #192 / N1 (#635) — in-repo stand-in for the future separate Apache-2.0 "matchers"
// repo. A plain-JVM Gradle build with NO Android deps, `includeBuild("matchers")`-ed
// by the app's root settings as the production default (no opt-in flag). Becomes a
// git submodule when the repo splits out (gated on #246).
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
