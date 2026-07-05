import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
    // #590 — standalone kotest-property (Arb/checkAll/forAll), runner-agnostic:
    // it runs inside plain JUnit-4 @Test bodies via runTest/runBlocking. NOT the
    // Kotest runner, NOT jqwik/Jazzer (both JUnit-Platform-only; this repo is JUnit 4).
    testImplementation(libs.kotest.property)
}

// ===========================================================================
// #192 / N1 (#635) — rule-distribution: matchers/rules/*.json5 is the SOLE rule
// source. The included `matchers` build canonicalizes it; this module imports
// the canonical output as GENERATED assets/rules/, consumed by BOTH the APK
// (via the AGP Variant API asset-merge wiring below) and the unit tests (which
// skip asset merge — see :app testDebugUnitTest dependsOn importMatchersRules).
// There are NO committed assets/rules/*.json anymore. Off-by-default guarding is
// gone: the composite build is the production default.
// See docs/adr/ADR-0009-rule-distribution-channels.md.
// ===========================================================================

// Resolve the canonicalized rules from the included `matchers` build via a
// custom attribute-matched configuration. `includeBuild("matchers")` (root
// settings) substitutes the local build for these coordinates. The custom
// Category attribute keeps AGP variant attributes out of the request, so a
// plain-JVM producer resolves without a "no matching variant" clash.
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

// Custom task (DirectoryProperty output — required so the AGP Variant API can
// wire it into asset merge; a Sync's File output cannot feed
// addGeneratedSourceDirectory). Copies the resolved canonical rules into
// <outDir>/rules/ so they land at assets/rules/ after merge.
abstract class ImportMatchersRules : DefaultTask() {
    @get:InputFiles
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        val rulesOut = outDir.get().dir("rules").asFile
        rulesOut.deleteRecursively()
        rulesOut.mkdirs()
        var count = 0
        source.files.forEach { f ->
            when {
                f.isDirectory ->
                    f.listFiles { c -> c.isFile && c.extension == "json" }?.forEach { j ->
                        j.copyTo(File(rulesOut, j.name), overwrite = true); count++
                    }
                f.isFile && f.extension == "json" -> {
                    f.copyTo(File(rulesOut, f.name), overwrite = true); count++
                }
            }
        }
        if (count == 0) {
            throw GradleException(
                "importMatchersRules resolved no rules/*.json from the matchers build " +
                    "(source: ${source.files}). The generated assets/rules/ would be empty.",
            )
        }
        logger.lifecycle("importMatchersRules: imported $count rule file(s) -> ${rulesOut.absolutePath}")
    }
}

val importMatchersRules = tasks.register<ImportMatchersRules>("importMatchersRules") {
    group = "matchers"
    description = "Import canonicalized rules from the matchers build into generated assets/rules/."
    source.from(matchersRules)
}

// APK path: register the generated dir as an assets source for every variant so
// AGP merges rules/*.json into the APK and orders the import task before merge.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            importMatchersRules,
            ImportMatchersRules::outDir,
        )
    }
}
