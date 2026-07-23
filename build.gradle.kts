// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
}

// Warnings-as-errors gate (#841): every Kotlin compilation in every module fails on ANY
// warning — hand-written or KSP-generated. A deliberate exception (e.g. the minSdk-30
// areBubblesAllowed fallback) gets a site-local @Suppress with a justifying comment, never
// a global opt-out. (The matchers included build has no Kotlin compilation tasks — its
// canonicalizer is build-script logic on the `base` plugin — so nothing to gate there.)
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }

    // JDK-25 test-worker noise (#841): third-party jars (robolectric, conscrypt, the
    // datastore protobuf) trip the native-access / sun.misc.Unsafe JVM notices on modern
    // JDKs. Not our code and not compiler-gateable — silenced at the test JVM. Version-
    // gated: CI runs JDK 21, where --sun-misc-unsafe-memory-access doesn't exist (23+)
    // and the notices don't fire anyway.
    tasks.withType<Test>().configureEach {
        // #590 property tests are memory-hungry by design — the recursion-fuzz suite
        // builds 100k-deep JSON structures and the mapper/classify fuzzers churn large
        // Mockito-inline mock graphs — and share one forked worker. Gradle's default
        // 512m test heap sat right at the edge (RuleCompileRecursionPropertyTest OOM'd
        // once a sibling fuzzer's footprint landed in the same JVM). Raise it so the
        // whole module fits deterministically; version-independent, so CI (JDK 21) gets
        // the same headroom.
        maxHeapSize = "2g"
        if (JavaVersion.current() >= JavaVersion.VERSION_24) {
            jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
        }
        // Robolectric appends the bootstrap classpath, which trips a harmless CDS
        // "Sharing is only supported for boot loader classes" notice — turn CDS off
        // for test workers rather than ship a permanent noise line.
        jvmArgs("-Xshare:off")
    }
}
