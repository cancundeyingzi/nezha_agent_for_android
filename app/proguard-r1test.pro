# Instrumentation is a separately compiled APK. AGP applies the target mapping to it, but cannot
# reconstruct shared methods/constructors whose signatures target-only optimization has removed.
# Preserve the shared library ABI only in r1test. Renaming is still allowed and mapped in the tests;
# private implementation code and the app_process helper implementation remain optimizable.
# These are NOT release/consumer rules. Never include this file in the release build type.
-keep,allowobfuscation class kotlin.** { public *; protected *; }
-keep,allowobfuscation class kotlinx.coroutines.** { public *; protected *; }
# Compose UI test and ActivityScenario call these platform/UI APIs across the APKs. DataStore and
# other AndroidX implementation packages are not blanket-kept; production callers own those paths.
-keep,allowobfuscation class androidx.compose.** { public *; protected *; }
-keep,allowobfuscation class androidx.lifecycle.** { public *; protected *; }
-keep,allowobfuscation class androidx.activity.** { public *; protected *; }
-keep,allowobfuscation class androidx.savedstate.** { public *; protected *; }
-keep,allowobfuscation class androidx.core.** { public *; protected *; }
-keep,allowobfuscation class androidx.collection.** { public *; protected *; }
-keep,allowobfuscation class androidx.arch.core.** { public *; protected *; }
# Runner and Espresso/ActivityScenario reference these shared types from the separate test APK.
-keep,allowobfuscation class androidx.tracing.** { public *; protected *; }
-keep,allowobfuscation class androidx.concurrent.futures.** { public *; protected *; }
-keep,allowobfuscation interface com.google.common.util.concurrent.ListenableFuture { public *; }
# Only the device migration seeder creates a legacy MasterKey/EncryptedSharedPreferences instance.
-keep,allowobfuscation class androidx.security.crypto.** { public *; protected *; }

# Explicit application ABI used by the immutable test application graph and device assertions.
# Service classes and MainViewModel already have production keep rules.
-keep,allowobfuscation class com.nezhahq.agent.NezhaAgentApplication { public *; protected *; }
-keep,allowobfuscation class com.nezhahq.agent.AppContainer { public *; }
-keep,allowobfuscation class com.nezhahq.agent.MainViewModel$Companion { public *; }
-keep,allowobfuscation class com.nezhahq.agent.NekogramLiquidGlassBarView
-keep,allowobfuscation class com.nezhahq.agent.grpc.ConnectionStateHolder { public *; }
-keep,allowobfuscation class com.nezhahq.agent.grpc.ManagedGrpcConnection { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.config.ConfigRepository** { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.config.StorageStatus { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.AgentConfig { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.ConnectionDraft { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.AutoStartState { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.KeepAliveSettings { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.RemoteCapabilities { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.RemoteCapability { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.model.SimulatorDraft { public *; }
-keep,allowobfuscation class com.nezhahq.agent.core.security.RootModeMutationCoordinator { public *; }

-keep,allowobfuscation class com.nezhahq.agent.util.AndroidConfigurationKeyProvider { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.AndroidLegacyConfigurationOperations { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.ConfigurationPersistence { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.ConfigurationStorageCoordinator { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.ConfigurationValues { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.StoredConfiguration { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.PublishedConfiguration { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.EncryptedConfigurationDataStore { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.LegacyConfigurationOperations { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.LegacyPlaintextConfiguration { public *; }
-keep,allowobfuscation class com.nezhahq.agent.executor.FileManager { public *; }
-keep,allowobfuscation class com.nezhahq.agent.util.ShellBackendPolicy { public *; }

-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.ApplicationHelperLauncher { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.McpHelperTransport { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperLifecycleOwner { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperDeadline { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperDeadline$Companion { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperCall { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperLaunchSpec { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperExchangeResult** { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.OwnedMcpResult { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperBackend { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.transport.HelperFailure { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.privileged.PrivilegedGateway** { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.privileged.PrivilegedService { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.privileged.PrivilegedHelperAuthority { public *; }
-keep,allowobfuscation class com.nezhahq.agent.mcp.privileged.PrivilegedHelperAuthority$Permit { public *; }
