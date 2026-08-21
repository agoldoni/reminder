package it.agoldoni.reminder.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * L'icona Material "restore" vive solo in `material-icons-extended`: 37 MB di artefatto per
 * un'icona sola, in un'app che ne usa sette. Ridefinirla qui tiene fuori quel peso dall'AppImage
 * e dall'APK; le altre sei stanno in `material-icons-core`.
 */
val RestoreIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Restore",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(RESTORE_PATH),
        fill = SolidColor(Color.Black)
    ).build()
}

private const val RESTORE_PATH =
    "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7" +
        "c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9z" +
        "m-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"
