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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
    // Saved across rotation: forgetting a denial would make the screen ask again by itself, and a second
    // denial is permanent on Android 11 and later.
    var code by rememberSaveable { mutableIntStateOf(if (granted()) CODE_GRANTED else CODE_NOT_ASKED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        code = if (isGranted) {
            CODE_GRANTED
        } else {
            val activity = context.findActivity()
            if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) CODE_DENIED else CODE_BLOCKED
        }
    }
    // Back from system settings: pick up a permission granted (or revoked) there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (granted()) code = CODE_GRANTED else if (code == CODE_GRANTED) code = CODE_DENIED
    }
    val access = when (code) {
        CODE_GRANTED -> CameraAccess.Granted
        CODE_DENIED -> CameraAccess.Denied(canAskAgain = true)
        CODE_BLOCKED -> CameraAccess.Denied(canAskAgain = false)
        else -> CameraAccess.NotAsked
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

private const val CODE_NOT_ASKED = 0
private const val CODE_GRANTED = 1
private const val CODE_DENIED = 2
private const val CODE_BLOCKED = 3

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
