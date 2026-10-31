# =============================================================================
# 哪吒探针 ProGuard / R8 混淆规则
# =============================================================================

# ── Shizuku API 混淆保留 ──────────────────────────────────────────────────────
# Shizuku 通过反射调用 ContentProvider 和 Binder 接口，
# 若被 R8 缩减/混淆会导致运行时 ClassNotFoundException 或 NoSuchMethodError。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ShizukuProvider 在 Manifest 中引用，必须保留全限定类名
-keep class rikka.shizuku.ShizukuProvider { *; }

# Shizuku UserService 相关（虽然当前未使用，但预留以备将来迁移）
-keep class * extends android.os.IInterface { *; }

# ── gRPC / Protobuf-lite 混淆保留 ────────────────────────────────────────────
-keep class com.google.protobuf.** { *; }
-keep class io.grpc.** { *; }
-keep class proto.** { *; }

# ── 应用自身的 AIDL 和 Service 类保留 ────────────────────────────────────────
-keep class com.nezhahq.agent.service.** { *; }

# ── gRPC 连接状态枚举（MVVM 架构中通过 StateFlow 驱动 UI 使用） ──────────────
-keep enum com.nezhahq.agent.grpc.GrpcConnectionState { *; }

# ── ViewModel（ViewModelProvider 通过反射实例化） ──────────────────────────────
-keep class com.nezhahq.agent.MainViewModel { *; }
# 忽略 gRPC 在 Android 上不需要的标准 Java JNDI 类
-dontwarn javax.naming.**

# 忽略 gRPC 引用的旧版 OkHttp 类
-dontwarn com.squareup.okhttp.**

# app_process loads exactly this static main by name. Its reachable code needs no DTO/reflection keep.
-keep class com.nezhahq.agent.mcp.helper.HelperMain {
    public static void main(java.lang.String[]);
}
