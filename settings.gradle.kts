pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// #192 / N1 (#635) — composite build (Option A), the production default. The
// local `matchers/` build owns the JSON5 rule source and is substituted for the
// `cloud.trotter.matchers:matchers` coordinates the app resolves; `:core:pipeline`
// canonicalizes it into GENERATED assets that both the APK and the tests consume.
// (When the matchers repo splits out — #192, gated on #246 — this becomes a git
// submodule.) See docs/adr/ADR-0009-rule-distribution-channels.md.
includeBuild("matchers")

rootProject.name = "DashBuddy"
include(":app")
include(":domain")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:location")
include(":core:data")
include(":core:datastore")
include(":core:pipeline")
include(":core:state")
include(":feature:settings")
include(":feature:setup")
include(":feature:dashboard")
