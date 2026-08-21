package it.agoldoni.reminder.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Come [RestoreIcon]: l'icona Material "sync" sta solo in `material-icons-extended`, e quei 37 MB
 * non entrano nell'APK per due icone.
 */
val SyncIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sync",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(SYNC_PATH),
        fill = SolidColor(Color.Black)
    ).build()
}

private const val SYNC_PATH =
    "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12" +
        "c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12" +
        "c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"
