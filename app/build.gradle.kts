plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
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
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

val grpcVersion = "1.62.2"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "3.25.3"

dependencies {

    // Pure-Kotlin domain rules. The dependency is one-way: :core must never depend on :app.
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Read-only migration from the legacy EncryptedSharedPreferences store.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OKHTTP for Trace and Tasks
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MCP uses explicit streaming adapters, never reflective DTO serialization.
    implementation("com.google.code.gson:gson:2.10.1")

    // gRPC & Protobuf
    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-protobuf-lite:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")

    // Shizuku API：提供 ADB 级别的高权限 Shell 执行能力，
    // 当设备无 Root 但安装了 Shizuku 应用时，可作为 su 的替代方案。
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // For javax.annotation.Generated

    testImplementation("junit:junit:4.13.2")
    // No androidTest dependencies: there is no app/src/androidTest source set. Espresso and
    // compose-ui-test were declared but never used, which made the project look like it had
    // instrumented coverage. Add them back alongside the first test that needs them.
    debugImplementation("androidx.compose.ui:ui-tooling")
}

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
