plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cloud.trotter.dashbuddy.core.pipeline"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 35

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
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    implementation(project(":domain"))

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
}

// ===========================================================================
// SPIKE (#192) — rule-distribution local dev loop. ALL of this is guarded by
// `-PuseLocalMatchers` (or omitted entirely); the default build is unchanged.
// See docs/adr/ADR-0009-rule-distribution-channels.md.
// ===========================================================================
if (providers.gradleProperty("useLocalMatchers").isPresent) {

    // ---- OPTION A (composite build / dependency substitution) probe --------
    // Resolve the canonicalized rules from the included `matchers` build via a
    // custom attribute-matched configuration. Substituted by `includeBuild`.
    val matchersRules: Configuration by configurations.creating {
        isCanBeResolved = true
        isCanBeConsumed = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "matchers-rules"))
        }
    }
    dependencies {
        add(matchersRules.name, "cloud.trotter.matchers:matchers:0.0.0-local")
    }
    val importDir = layout.buildDirectory.dir("generated/matchers-optionA/rules")
    val importMatchers = tasks.register<Sync>("importMatchersOptionA") {
        group = "matchers"
        description = "OPTION A probe: import canonicalized rules resolved from the included matchers build."
        from(matchersRules)
        into(importDir)
    }

    // ---- Faithful-canonicalization PROOF (re-runnable) ---------------------
    // Assert the canonicalized artifact the app would CONSUME is byte-identical
    // to the committed baseline asset. This is the spike's strongest claim:
    // JSON5 source (uber.json5, with a comment + trailing comma) canonicalizes
    // back to the exact committed uber.json.
    tasks.register("verifyMatchersCanonical") {
        group = "matchers"
        description = "PROOF: canonicalized uber.json == committed assets/rules/uber.json (byte-identical)."
        dependsOn(importMatchers)
        val committed = layout.projectDirectory.file("src/main/assets/rules/uber.json")
        val generated = importDir.map { it.file("uber.json") }
        inputs.file(committed)
        inputs.file(generated)
        doLast {
            val a = generated.get().asFile.readBytes()
            val b = committed.asFile.readBytes()
            if (!a.contentEquals(b)) {
                throw GradleException(
                    "Canonicalized uber.json (${a.size} bytes) does NOT match committed " +
                        "assets/rules/uber.json (${b.size} bytes) — canonicalization is not faithful.",
                )
            }
            logger.lifecycle(
                "PROOF OK: canonicalized uber.json is byte-identical to committed " +
                    "assets/rules/uber.json (${a.size} bytes).",
            )
        }
    }
}
