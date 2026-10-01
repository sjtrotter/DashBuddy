plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    // #1173: substituted from the census-contract included build; api exposes the contract
    // transitively (:core:pipeline reads TextFold/SensitiveMarkerData, :app tests read DTOs).
    api("cloud.trotter.census:contract:0.0.0-local")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.reflect)
}

// #1173 review: :domain:test — which CI already runs — also runs the contract's tests,
// because :domain is the contract's first consumer.
tasks.test {
    dependsOn(gradle.includedBuild("census-contract").task(":test"))
}
