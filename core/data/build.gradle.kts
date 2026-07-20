plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cloud.trotter.dashbuddy.core.data"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.timber)
    // Room types surface here because AppEventRepo owns the insert+mark
    // transaction boundary (withTransaction needs RoomDatabase, #354).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:location"))
    implementation(project(":core:network"))
    implementation(project(":domain"))

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // #244 — relocated analytics/odometer tests (from :app) need Robolectric (Room
    // in-memory DB via RuntimeEnvironment.getApplication()), mockito-kotlin, and
    // kotlinx-coroutines-test (runTest/TestDispatcher). Same pattern as
    // core/database's DatabaseBackupTest (#690).
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}