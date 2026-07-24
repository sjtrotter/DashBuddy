plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cloud.trotter.dashbuddy.feature.bubble"
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
        // OfferGaugeBitmapTest is a Robolectric test (android.graphics Canvas/Bitmap); the
        // compiled resources let Robolectric resolve a real manifest/Context.
        unitTests.isIncludeAndroidResources = true
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

    testImplementation(libs.junit)
    // OfferGaugeBitmapTest exercises the Canvas/Bitmap gauge draw under Robolectric.
    testImplementation(libs.robolectric)
}
