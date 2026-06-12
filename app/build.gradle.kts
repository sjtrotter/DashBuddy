import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cloud.trotter.dashbuddy"
    compileSdk = 36

    defaultConfig {
        applicationId = "cloud.trotter.dashbuddy"
        minSdk = 35
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.230.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("keystore.path")
            val storePass = System.getenv("STORE_PASSWORD")
                ?: localProperties.getProperty("keystore.store.password")
            val keyAliasVal = System.getenv("KEY_ALIAS")
                ?: localProperties.getProperty("keystore.key.alias")
            // KEY_PASSWORD is optional — if not set, falls back to store password
            val keyPass = System.getenv("KEY_PASSWORD")
                ?: localProperties.getProperty("keystore.key.password")

            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = storePass
                keyAlias = keyAliasVal
                // Fall back to store password when no separate key password was set
                keyPassword = keyPass.takeIf { !it.isNullOrBlank() } ?: storePass
            }
        }
    }

    buildTypes {
        release {
            // R8 posture (#434): minify stays OFF until the remaining
            // reflective surfaces are keep-ruled or removed — Gson over
            // EventMetadata in DashBuddyApplication.createMetadata(), and
            // kotlinx-serialization's generated serializers are safe but
            // unaudited under shrinking. The hot-path reflection that made
            // this dangerous (EffectMap's gate extraction) was replaced by
            // sealed ParsedFields.toFieldMap() in #434. Flipping this on
            // without that audit silently breaks reflective code paths.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.all { test ->
            test.jvmArgs("-XX:+EnableDynamicAgentLoading")

            // JUnit @Suite aggregators (e.g. AllMatchersSuite) re-run their member
            // classes, so during a broad sweep the members would run twice — once
            // directly, once via the suite. Exclude suites from an unfiltered sweep;
            // a suite stays runnable explicitly via --tests "*AllMatchersSuite*"
            // (which sets commandLineIncludePatterns, so the exclude is skipped).
            // Done in doFirst because --tests is only applied to the filter at
            // execution time.
            test.doFirst {
                val filter = test.filter as
                    org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
                if (filter.commandLineIncludePatterns.isEmpty()) {
                    filter.excludeTestsMatching("*Suite")
                }
            }
        }
    }

    viewBinding.enable = true

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

dependencies {
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.code.gson)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    implementation(libs.play.services.location)
    implementation(libs.reorderable)
    testImplementation(libs.robolectric)
    implementation(libs.timber)
    implementation(platform(libs.androidx.compose.bom))

    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:location"))
    implementation(project(":core:network"))
    implementation(project(":core:pipeline"))
    implementation(project(":core:state"))
    implementation(project(":domain"))

    ksp(libs.androidx.hilt.compiler)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.assign(JvmTarget.JVM_21)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}