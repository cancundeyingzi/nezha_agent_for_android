plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
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
            // Exercise the release R8 rules in a debuggable, separately installable package.
            initWith(getByName("release"))
            applicationIdSuffix = ".r1test"
            manifestPlaceholders["appLabel"] = "哪吒探针 R1测试"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = true
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
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
    // No androidTest dependencies: there is no app/src/androidTest source set. Espresso and
    // compose-ui-test were declared but never used, which made the project look like it had
    // instrumented coverage. Add them back alongside the first test that needs them.
    debugImplementation(libs.androidx.compose.ui.tooling)
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
