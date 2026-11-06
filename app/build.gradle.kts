import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import com.android.build.gradle.internal.tasks.R8Task

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

// AGP 8.6 normally traces each APK independently for L8. Instrumentation loads the test APK's j$
// classes first, so a test-only backport can otherwise shadow an app backport with missing methods.
// Trace the same union for BOTH r1test APKs; release retains its own normal shrinking inputs.
tasks.withType<L8DexDesugarLibTask>().configureEach {
    if (name == "l8DexDesugarLibR1test" || name == "l8DexDesugarLibR1testAndroidTest") {
        dexFiles.from(
            tasks.named<R8Task>("minifyR1testWithR8").flatMap { it.outputDex },
            tasks.named<R8Task>("minifyR1testAndroidTestWithR8").flatMap { it.outputDex }
        )
    }
}

android {
    namespace = "com.nezhahq.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nezhahq.agent"
        minSdk = 23
        targetSdk = 35
        versionCode = 386
        versionName = "A0.11.0-r1"
        testInstrumentationRunner = "com.nezhahq.agent.device.DeviceTestRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "60000"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("r1test") {
            // A debug signature permits instrumentation; debuggable=false keeps R8 optimization on.
            initWith(getByName("release"))
            applicationIdSuffix = ".r1test"
            manifestPlaceholders["appLabel"] = "哪吒探针 R1测试"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles("proguard-r1test.pro")
            matchingFallbacks += listOf("debug")
        }
    }
    testBuildType = "r1test"
    compileOptions {
        // Shizuku listener removal uses Predicate/List.removeIf, which Android 6 does not provide.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // An unmocked Android call fails the test instead of quietly returning 0/false/null, so a
        // unit test cannot pass against behaviour the device would never produce. Tests whose code
        // path logs need SilentLoggerRule; see its documentation.
        unitTests.isReturnDefaultValues = false
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {

    // Pure-Kotlin domain rules. The dependency is one-way: :core must never depend on :app.
    implementation(project(":core"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Read-only migration from the legacy EncryptedSharedPreferences store.
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.core)

    // OKHTTP for Trace and Tasks
    implementation(libs.okhttp)

    // MCP uses explicit streaming adapters, never reflective DTO serialization.
    implementation(libs.gson)

    // gRPC & Protobuf
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.javalite)

    // Shizuku API：提供 ADB 级别的高权限 Shell 执行能力，
    // 当设备无 Root 但安装了 Shizuku 应用时，可作为 su 的替代方案。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    compileOnly(libs.tomcat.annotations.api) // For javax.annotation.Generated

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Runner 1.6.2 calls Trace.forceEnableAppTracing, absent from release's transitive 1.0.0.
    // AGP aligns shared test dependencies to the tested variant, so align this variant explicitly.
    add("r1testImplementation", libs.androidx.tracing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Local unit tests run on the host JVM. The Android variant sees mock SDK_INT=0 and uses
// File.renameTo, whose replacement semantics differ on Windows. Exercise the official JVM
// DataStore implementation here; APKs and instrumented tests keep the Android implementation.
val dataStoreVersion = libs.versions.androidxDataStore.get()
configurations.matching {
    it.name.endsWith("UnitTestCompileClasspath") || it.name.endsWith("UnitTestRuntimeClasspath")
}.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.datastore:datastore-core"))
            .using(module("androidx.datastore:datastore-core-jvm:$dataStoreVersion"))
            .because("Local unit tests need the host filesystem implementation")
    }
}

// The protobuf plugin configures codegen with plain coordinate strings rather than dependency
// notations, so these three read the version out of the catalog instead of aliasing a library.
val grpcVersion = libs.versions.grpc.get()
val grpcKotlinVersion = libs.versions.grpcKotlin.get()
val protobufVersion = libs.versions.protobuf.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
                create("grpckt") {
                    option("lite")
                }
            }
        }
    }
}
