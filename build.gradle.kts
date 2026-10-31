// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Versions live in gradle/libs.versions.toml; nothing here should carry a version literal.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
}
