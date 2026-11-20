package com.aicarcompanion.app.vin.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Where the camera permission stands. The VIN can always be typed, whatever this says. */
sealed interface CameraAccess {
    data object NotAsked : CameraAccess
    data object Granted : CameraAccess
    /** Denied. [canAskAgain] is false once Android won't show the prompt again (settings only). */
    data class Denied(val canAskAgain: Boolean) : CameraAccess
}

class CameraAccessController(
    val access: CameraAccess,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/** Asks for the camera only when the scanner is opened, and notices when the user changes it in settings. */
@Composable
fun rememberCameraAccess(): CameraAccessController {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var access by remember { mutableStateOf<CameraAccess>(if (granted()) CameraAccess.Granted else CameraAccess.NotAsked) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        access = if (isGranted) {
            CameraAccess.Granted
        } else {
            val activity = context.findActivity()
            CameraAccess.Denied(activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA))
        }
    }
    // Back from system settings: pick up a permission granted (or revoked) there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (granted()) access = CameraAccess.Granted else if (access is CameraAccess.Granted) access = CameraAccess.Denied(canAskAgain = true)
    }
    return CameraAccessController(
        access = access,
        request = { launcher.launch(Manifest.permission.CAMERA) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
