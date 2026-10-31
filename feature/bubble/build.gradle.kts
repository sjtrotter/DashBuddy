plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cloud.trotter.log.gig.feature.bubble"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 30

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric hosts bitmap, offer text and Compose UI tests;
        // compiled resources let it resolve a real manifest/Context.
        unitTests.isIncludeAndroidResources = true
        // #1463: `-PscreenShots=true` renders the HUD screenshots (`*ScreenshotsTest`) into
        // build/outputs/screenshots for PR CI's `screenshots-<pr>` artifact. Without the property the
        // classes compile and never run, so the gate stays free of them.
        val screenShots = providers.gradleProperty("screenShots").orNull == "true"
        val shotsDir = layout.buildDirectory.dir("outputs/screenshots").get().asFile
        unitTests.all { test ->
            test.systemProperty("giglog.screenshots.dir", shotsDir.absolutePath)
            test.systemProperty("roborazzi.test.record", screenShots.toString())
            if (screenShots) {
                // A cached test task must never stand in for a render, and the render's results go
                // to their own directories so the gate's JUnit XML is never replaced.
                test.outputs.dir(shotsDir)
                test.outputs.upToDateWhen { false }
                test.outputs.cacheIf { false }
                test.reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/screenshots"))
                test.binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/screenshots-bin"))
            } else {
                test.filter.excludeTestsMatching("*ScreenshotsTest")
            }
        }
    }
}

dependencies {
    // Honest deps (declare only what's used): the bubble HUD renderers + card model + formatters
    // read :domain types and render with :core:designsystem components. No Hilt/:core:data/:core:state
    // — the injected BubbleViewModel/BubbleManager stay in :app (BubbleViewModel injects the
    // :app-coupled BubbleManager; moving it would need an inversion, #96 boundary).
    implementation(project(":domain"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material.icons.extended)
    // Compose @Preview tooling — feature (UI) modules are where previews live; kept as
    // conscious boilerplate (no @Preview yet).
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit)
    // Offer text and pay bar tests resolve Android resources under Robolectric.
    testImplementation(libs.robolectric)
    // #1463: Roborazzi captures the HUD screenshots (HudScreenshotsTest, run only with -PscreenShots=true).
    testImplementation(libs.roborazzi)
    // #1333: Compose UI tests under Robolectric (the HUD's reactivity rules are tested here).
    testImplementation(libs.androidx.ui.test.junit4)
    // Debug only: the Compose tests live in src/testDebug. :app's debug manifest keeps the host activity unexported.
    debugImplementation(libs.androidx.ui.test.manifest)
}
