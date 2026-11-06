package com.nezhahq.agent.device

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityOptionsCompat
import com.nezhahq.agent.ui.NotificationPermissionRequestHost

/** R1-only host: real Activity/Compose/registry restoration, with no system permission dialog. */
class NotificationPermissionTestActivity : ComponentActivity() {
    private var pendingRequestId by mutableStateOf<Long?>(null)
    private val launchedCodes = mutableListOf<Int>()
    private val deliveredIds = mutableListOf<Long>()
    private val deliveredPermissions = mutableListOf<Boolean>()
    private val permissionRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            check(contract is ActivityResultContracts.RequestPermission)
            check(input == Manifest.permission.POST_NOTIFICATIONS)
            // Deliberately defer the result. This remains controlled even when the OS permission
            // is already granted or the API level predates the notification permission dialog.
            launchedCodes += requestCode
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = permissionRegistry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            pendingRequestId = if (savedInstanceState.containsKey(PENDING_ID)) savedInstanceState.getLong(PENDING_ID) else null
            launchedCodes.addAll(savedInstanceState.getIntegerArrayList(LAUNCHED_CODES).orEmpty())
            savedInstanceState.getLongArray(DELIVERED_IDS)?.let { deliveredIds.addAll(it.toList()) }
            savedInstanceState.getBooleanArray(DELIVERED_GRANTS)?.let { deliveredPermissions.addAll(it.toList()) }
            permissionRegistry.onRestoreInstanceState(savedInstanceState.getBundle(REGISTRY_STATE))
        }
        setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                NotificationPermissionRequestHost(pendingRequestId) { requestId, granted ->
                    deliveredIds += requestId
                    deliveredPermissions += granted
                    // A stale result is observed by the fixture, but does not consume a successor.
                    // The production RunControlController independently tests that rejection.
                    if (pendingRequestId == requestId) pendingRequestId = null
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingRequestId?.let { outState.putLong(PENDING_ID, it) }
        outState.putIntegerArrayList(LAUNCHED_CODES, ArrayList(launchedCodes))
        outState.putLongArray(DELIVERED_IDS, deliveredIds.toLongArray())
        outState.putBooleanArray(DELIVERED_GRANTS, deliveredPermissions.toBooleanArray())
        outState.putBundle(REGISTRY_STATE, Bundle().also(permissionRegistry::onSaveInstanceState))
        // ComponentActivity saves the actual rememberSaveable/launcher-key registry in this bundle.
        super.onSaveInstanceState(outState)
    }

    fun setPendingRequest(requestId: Long?) { pendingRequestId = requestId }
    fun launchCount(): Int = launchedCodes.size
    fun launchedRequestCode(index: Int): Int = launchedCodes[index]
    fun deliveredRequestIds(): LongArray = deliveredIds.toLongArray()
    fun deliveredGrants(): BooleanArray = deliveredPermissions.toBooleanArray()
    fun registryInstanceForTest(): ActivityResultRegistry = permissionRegistry

    fun dispatchPermissionResult(requestCode: Int, granted: Boolean): Boolean = permissionRegistry.dispatchResult(
        requestCode, Activity.RESULT_OK,
        Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
    )

    private companion object {
        const val PENDING_ID = "fixture.pendingId"
        const val LAUNCHED_CODES = "fixture.launchedCodes"
        const val DELIVERED_IDS = "fixture.deliveredIds"
        const val DELIVERED_GRANTS = "fixture.deliveredGrants"
        const val REGISTRY_STATE = "fixture.registryState"
    }
}
