package com.nezhahq.agent.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.nezhahq.agent.MainViewModel

/** Lives in the Activity's outer composition, whose saveable registry survives recreation.
 * The nested page ComposeView shares NO_ID with that root and must not own this bridge.
 */
@Composable
internal fun NotificationPermissionHost(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    // Persist the ID actually submitted to ActivityResultRegistry. A later pending intent must
    // never borrow an earlier request's callback, including across Activity recreation.
    var launchedNotificationRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var completedNotificationRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requestId = launchedNotificationRequestId
        launchedNotificationRequestId = null
        if (requestId != null) {
            completedNotificationRequestId = requestId
            vm.onNotificationPermissionResult(requestId, granted)
        }
    }
    val pendingNotificationId = state.run.pendingStart?.requestId
    LaunchedEffect(pendingNotificationId, launchedNotificationRequestId) {
        if (pendingNotificationId != null && launchedNotificationRequestId == null &&
            completedNotificationRequestId != pendingNotificationId) {
            launchedNotificationRequestId = pendingNotificationId
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

}
