package com.aicarcompanion.app.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.aicarcompanion.app.BuildConfig
import com.aicarcompanion.app.ui.theme.Penelope

/** Version text generated from the build configuration (never hard-coded). */
object AppBuildInfo {
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    /** Shown on the home screen in debug/beta builds only; release builds can hide or move it. */
    val showOnHome: Boolean = BuildConfig.SHOW_BUILD_LABEL

    fun label(name: String = versionName, code: Int = versionCode) = "v$name • Build $code"
    fun spoken(name: String = versionName, code: Int = versionCode) = "App version $name, build $code"
}

/** Small, unobtrusive version label for testers. */
@Composable
fun BuildLabel(modifier: Modifier = Modifier) {
    Text(
        AppBuildInfo.label(),
        style = MaterialTheme.typography.labelSmall,
        color = Penelope.tokens.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { contentDescription = AppBuildInfo.spoken() },
    )
}
