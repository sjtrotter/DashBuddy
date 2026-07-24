plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cloud.trotter.dashbuddy.feature.dashboard"
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
}

dependencies {
    // Honest deps (the #851/#852 "declare only what's used" doctrine — cf. the #852-F1
    // dead-designsystem drop). The extracted surface is a handful of purely presentational
    // dashboard composables (data-in / lambdas-out): they read `:domain` value types
    // (AnalyticsPeriod / PeriodEconomics / Formats) and the `:core:designsystem` component
    // library (AppCard / AppSegmented / AppStatTile / AppTheme). No `@HiltViewModel`, no
    // `@Inject`, no repositories — so NO Hilt/KSP, NO `:core:data`, NO Timber here (the
    // Dashboard's ViewModel/state + Hilt wiring stay in `:app`; see the PR body).
    implementation(project(":domain"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.foundation)
    // Compose @Preview tooling — feature (UI) modules are where previews live; kept as
    // conscious boilerplate (the redesign work of Epic #320 will use it; no @Preview yet).
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
}
