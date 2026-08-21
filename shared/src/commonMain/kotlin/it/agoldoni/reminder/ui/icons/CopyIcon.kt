package it.agoldoni.reminder.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Come [RestoreIcon] e [SyncIcon]: l'icona Material "content_copy" sta solo in
 * `material-icons-extended`, e quei 37 MB non entrano nell'APK per tre icone. In `core` ce ne
 * sono 56 e questa non è fra loro; c'era `Share`, che però promette un'altra cosa.
 */
val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(COPY_PATH),
        fill = SolidColor(Color.Black)
    ).build()
}

private const val COPY_PATH =
    "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7" +
        "c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
