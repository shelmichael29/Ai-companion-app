package com.aicarcompanion.app.ui.drive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons for Drive, in the same style as the rest of the app's icons. */
internal object DriveIcons {
    private fun line(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Next = line("next", "M6,6 L14,12 L6,18 Z", "M18,6 V18")
    val Previous = line("previous", "M18,6 L10,12 L18,18 Z", "M6,6 V18")
    val VolumeUp = line("volume_up", "M4,10 H8 L13,6 V18 L8,14 H4 Z", "M16,9 A4,4 0 0 1 16,15", "M18.5,6.5 A8,8 0 0 1 18.5,17.5")
    val VolumeDown = line("volume_down", "M4,10 H8 L13,6 V18 L8,14 H4 Z", "M16,9.5 A3.5,3.5 0 0 1 16,14.5")
    val Phone = line(
        "phone",
        "M6,4 H10 L11.5,8.5 L9.5,10 C10.5,12 12,13.5 14,14.5 L15.5,12.5 L20,14 V18 A2,2 0 0 1 18,20 C10,20 4,14 4,6 A2,2 0 0 1 6,4 Z",
    )
}
