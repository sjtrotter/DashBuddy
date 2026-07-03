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

// SPIKE (#192) — guarded composite build (Option A). OFF by default so the
// default build is byte-for-byte unchanged; opt in with `-PuseLocalMatchers`.
// When present, the local `matchers/` build is substituted for the
// `cloud.trotter.matchers:matchers` coordinates the app would otherwise resolve
// from a repo. See docs/adr/ADR-0009-rule-distribution-channels.md.
if (providers.gradleProperty("useLocalMatchers").isPresent) {
    includeBuild("matchers")
}

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
