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

// ---------------------------------------------------------------------------
// Build identity (PR #1066): every build must be able to say which commit it is.
//
// The receipt: a field-data pull on 2026-09-05 had to INFER the phone's build
// from the ABSENCE of log lines, because `:app:installDebug` reported the same
// `versionName=0.230.0` for a branch build as for the master build it replaced.
// So the git sha rides the versionName, BuildConfig, the startup INFO line, and
// the periodic PipelineStats summary.
//
// Fail-safe by construction: every git read is wrapped, and a missing git / a
// non-repo checkout (a source zip, a CI shallow export without .git) yields
// "nogit" rather than failing the build.
// ---------------------------------------------------------------------------

/**
 * Runs a git command through `providers.exec` (configuration-cache friendly) and returns its
 * trimmed stdout, or `null` if git is absent, this is not a repo, or the command failed.
 */
fun gitOutput(vararg args: String): String? = try {
    val out = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim().ifEmpty { null }
    else null
} catch (_: Exception) {
    null
}

val gitSha: String = gitOutput("rev-parse", "--short=8", "HEAD") ?: "nogit"

/**
 * Working tree dirty? `.idea/` paths are EXCLUDED on purpose: Android Studio rewrites
 * `.idea/gradle.xml` constantly, so counting it would mark every developer build `.dirty`
 * and the marker would stop meaning anything.
 */
val gitDirty: Boolean = (gitOutput("status", "--porcelain") ?: "")
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    // porcelain v1 is `XY <path>`; a rename is `R  old -> new` — take the last path either way.
    .map { line -> line.substringAfter(' ').trim().trim('"').substringAfterLast("-> ").trim('"') }
    .any { !it.startsWith(".idea/") }

/**
 * Configuration-time wall clock. Deliberately NOT `Instant.now()` inside a task: the value must
 * be baked into `BuildConfig`, which is generated from configuration. Note that under an ENABLED
 * configuration cache a reused configuration would freeze this at the cached instant — the sha is
 * the identity that matters, this is only a convenience.
 */
val buildTimeMs: Long = System.currentTimeMillis()


android {
    namespace = "cloud.trotter.log.gig"
    compileSdk = 36

    defaultConfig {
        applicationId = "cloud.trotter.log.gig"
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // versionCode stays a hand-bumped constant. It must NOT be derived from git (a commit
        // count, a timestamp): Android refuses to install an APK whose versionCode is LOWER than
        // the installed one, so a derived-monotonic code would make installing a branch build
        // over a newer master build fail with INSTALL_FAILED_VERSION_DOWNGRADE. The build's
        // identity rides versionName + BuildConfig.GIT_SHA instead, which nothing enforces
        // ordering on.
        versionCode = 1

        // The base marketing version, hand-bumped. The `+<sha>` suffix is what makes each
        // build self-identifying; `.dirty` marks an uncommitted working tree.
        val baseVersionName = "0.230.0"
        versionName = "$baseVersionName+$gitSha" + if (gitDirty) ".dirty" else ""

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("long", "BUILD_TIME_MS", "${buildTimeMs}L")
    }

    signingConfigs {
        // #1469: the test build signs with one PRIVATE key, so every CI run and every local build signs it
        // the same way and an update installs in place. Private because the test build holds real field
        // data and census credentials: a published key would let anyone sign an accepted update. CI passes
        // DEBUG_KEYSTORE_PATH + DEBUG_KEYSTORE_PASSWORD (repo secrets); the laptop sets
        // giglog.debugKeystore.path + giglog.debugKeystore.passwordFile in ~/.gradle/gradle.properties.
        // Without either, Android's default debug key signs, as before.
        getByName("debug") {
            fun nonBlank(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
            val envPath = nonBlank(System.getenv("DEBUG_KEYSTORE_PATH"))
            val propPath = nonBlank(providers.gradleProperty("giglog.debugKeystore.path").orNull)
            // One source at a time: CI's environment when it sets a path, else the laptop's properties.
            val (path, password) = when {
                envPath != null -> envPath to nonBlank(System.getenv("DEBUG_KEYSTORE_PASSWORD"))
                propPath != null -> {
                    val passFile = nonBlank(providers.gradleProperty("giglog.debugKeystore.passwordFile").orNull)
                        ?.let { file(it) }
                    require(passFile?.isFile == true) {
                        "giglog.debugKeystore.path is set but giglog.debugKeystore.passwordFile is missing or unreadable"
                    }
                    propPath to nonBlank(passFile!!.readText())
                }
                else -> null to null // not configured: Android's default debug key signs
            }
            if (path != null) {
                require(file(path).isFile) { "debug keystore not found at $path" }
                require(password != null) { "debug keystore at $path has no password configured" }
                storeFile = file(path)
                storePassword = password
                keyAlias = "giglogdebug"
                keyPassword = password
            }
        }
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
        debug {
            // The sideloaded test build installs beside the Play build: Android refuses to replace an
            // app signed with a different key, so the two need different application IDs (#1411).
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 posture (#434): minify stays OFF until the remaining
            // reflective surfaces are keep-ruled or removed — Gson over
            // EventMetadata in GigLogApplication.createMetadata(), and
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
        // Robolectric needs the compiled app resources (not just the SDK's) to resolve a
        // @StringRes id via a real Context — e.g. AnalyticsTabTest's resolved-label guard (#428).
        // Without this, Context.getString() throws Resources.NotFoundException in a unit test.
        unitTests.isIncludeAndroidResources = true
        // #1463: `-PscreenShots=true` renders the key-screen screenshots (`*ScreenshotsTest`) into
        // build/outputs/screenshots for PR CI's `screenshots-<pr>` artifact. Without the property the
        // classes compile and never run, so the gate stays free of them.
        val screenShots = providers.gradleProperty("screenShots").orNull == "true"
        val shotsDir = layout.buildDirectory.dir("outputs/screenshots").get().asFile
        unitTests.all { test ->
            test.jvmArgs("-XX:+EnableDynamicAgentLoading")

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

            // Forward the parse-output golden regen flag to the forked test JVM
            // (#433/#462): `-DupdateParseGolden=true` is set on the Gradle JVM,
            // but the test runs in a fork — without this it never reaches
            // System.getProperty(...) and the documented regen silently no-ops.
            System.getProperty("updateParseGolden")?.let {
                test.systemProperty("updateParseGolden", it)
            }
            // #1160 review AI1: same forwarding for the framework-class inventory regen.
            System.getProperty("updateFrameworkClasses")?.let {
                test.systemProperty("updateFrameworkClasses", it)
            }
            // #1173: same forwarding for the census server conformance golden export.
            System.getProperty("exportCensusGolden")?.let {
                test.systemProperty("exportCensusGolden", it)
            }

            // Two families are excluded from an UNFILTERED sweep. Both stay runnable by
            // name (`--tests "*Foo*"` sets commandLineIncludePatterns, so the whole block
            // is skipped). Done in doFirst because --tests is only applied to the filter
            // at execution time.
            //
            // 1. JUnit @Suite aggregators (e.g. AllMatchersSuite) re-run their member
            //    classes, so during a broad sweep the members would run twice — once
            //    directly, once via the suite.
            // 2. Corpus-MUTATING intake tools (#941, review §4.4 item 5). InboxProcessorTest
            //    and UnknownScreenAnalysisTest redact/move/prune/delete files under
            //    `src/test/resources/snapshots/` as their normal operation — they are the
            //    documented triage workflow, not verification. On CI they are no-ops only
            //    because INBOX/ is gitignored and therefore empty there; that is luck, not a
            //    guarantee, and a developer's local sweep DOES hit a populated INBOX and
            //    silently rewrites the corpus mid-sweep. The exclusion makes "a sweep never
            //    mutates the corpus" structural. Run them deliberately:
            //      ./gradlew :app:testDebugUnitTest --tests "*InboxProcessorTest"
            val corpusMutatingIntakeTests = listOf(
                "*InboxProcessorTest",
                "*UnknownScreenAnalysisTest",
            )
            test.doFirst {
                val filter = test.filter as
                    org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
                if (filter.commandLineIncludePatterns.isEmpty()) {
                    filter.excludeTestsMatching("*Suite")
                    corpusMutatingIntakeTests.forEach(filter::excludeTestsMatching)
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

    // #1162: PR CI gates only `:app:lintVitalRelease`. With `checkDependencies` the one vital run also analyzes
    // every library module, and the root `lint.xml` (the severity owner) promotes `NewApi` to fatal there too.
    lint {
        checkDependencies = true
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
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp)
    testImplementation(libs.robolectric)
    // #1463: Roborazzi captures the key-screen screenshots (ScreenshotsTest, run only with -PscreenShots=true).
    testImplementation(libs.roborazzi)
    // #1333: Compose UI tests under Robolectric (the HUD's reactivity rules are tested here).
    testImplementation(libs.androidx.ui.test.junit4)
    // Debug only: the Compose tests live in src/testDebug. :app's debug manifest keeps the host activity unexported.
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.timber)
    implementation(platform(libs.androidx.compose.bom))

    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:location"))
    implementation(project(":core:network"))
    implementation(project(":core:pipeline"))
    // #1160 review AD4: the census corpus tests' frame-rule-off diagnostic (test fixtures only).
    testImplementation(testFixtures(project(":core:pipeline")))
    implementation(project(":core:state"))
    implementation(project(":domain"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:bubble"))

    ksp(libs.androidx.hilt.compiler)
    ksp(libs.hilt.compiler)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    // #590 — standalone kotest-property (Arb/checkAll/forAll) for the phase-2
    // ingestion property tests that live in the app test tree (they need
    // TestRulesetFactory + SessionReplay, which resolve here). Same runner-agnostic
    // JUnit-4 usage as :core:pipeline; NOT the Kotest runner, NOT jqwik/Jazzer.
    testImplementation(libs.kotest.property)
    // #314 — Robolectric DAO round-trip tests build an in-memory GigLogDatabase;
    // Room's builder API is only transitively runtime-visible via :core:database
    // (implementation), so surface it on the test compile classpath. room-ktx (the
    // suspend/Flow DAO runtime) is already transitive on the test runtime classpath.
    testImplementation(libs.androidx.room.runtime)
}

kotlin {
    compilerOptions {
        jvmTarget.assign(JvmTarget.JVM_21)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// #635 — recognition rules are GENERATED (matchers/rules/*.json5 canonicalized by
// :core:pipeline:importMatchersRules), not committed under assets/rules/. JVM unit
// tests read them off the filesystem via TestRulesetFactory and do NOT run AGP
// asset merge, so make the generate task an explicit predecessor of every unit-test
// task (testDebugUnitTest / testReleaseUnitTest). Without this the generated dir can
// be absent/stale on a clean build and TestRulesetFactory would fail fast.
tasks.withType<Test>().configureEach {
    dependsOn(":core:pipeline:importMatchersRules")
}
// #1160 review AK4 — the census framework-class inventory is generated from the RELEASE runtime classpath
// (plus the compileSdk boot classpath, i.e. android.jar), never the debug one: debug-only artifacts
// (`androidx.compose.ui.tooling.*`, leakcanary) must not be exempted. This task only LISTS the jars;
// `FrameworkClassInventoryTest` scans them (diff, or regenerate with -DupdateFrameworkClasses=true).
abstract class CensusReleaseClasspath : DefaultTask() {
    @get:InputFiles
    abstract val jars: ConfigurableFileCollection

    @get:OutputFile
    abstract val out: RegularFileProperty

    @TaskAction
    fun run() {
        val list = jars.files.filter { it.isFile && it.name.endsWith(".jar") }.map { it.absolutePath }.sorted()
        if (list.isEmpty()) throw GradleException("censusReleaseClasspath resolved no jars")
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(list.joinToString("\n", postfix = "\n"))
    }
}

val censusReleaseClasspath = tasks.register<CensusReleaseClasspath>("censusReleaseClasspath") {
    group = "census"
    description = "Lists the release runtime classpath jars + android.jar for the census framework-class inventory."
    jars.from(
        configurations.named("releaseRuntimeClasspath").map { cfg ->
            cfg.incoming.artifactView {
                attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
            }.files
        },
    )
    jars.from(androidComponents.sdkComponents.bootClasspath)
    out.set(layout.buildDirectory.file("census/release-classpath.txt"))
}

tasks.withType<Test>().configureEach {
    dependsOn(censusReleaseClasspath)
}
