plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

/*
 * Pure Kotlin on purpose: this module holds configuration validation, remote capability policy and
 * the privileged-access boundary. Keeping the Android SDK off its classpath means those rules can
 * never silently start depending on a device, and their tests run on a real JVM rather than against
 * `:app`'s stubbed `android.jar`, where every unmocked call throws.
 *
 * Depend on this module from `:app`, never the other way around.
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    testImplementation(libs.junit)
}
