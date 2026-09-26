package com.collinpendleton.yana.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The glyphs the chrome shares with the web client — Lucide (ISC),
 * 24-unit grid, 1.75-unit round stroke, tinted by the colour of
 * whatever holds them. Only what this app's screens use; add to the
 * map, not the bundle.
 */
object YanaIcons {
    /** A revision clock: the history screen and the feed's entry points. */
    val History: ImageVector by lazy { build("history",
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
        "M12 7v5l4 2",
    ) }

    /** The counterclockwise arrow every restore action carries. */
    val Restore: ImageVector by lazy { build("restore",
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
    ) }

    /** A person, the author chip of a person-made change. */
    val User: ImageVector by lazy { build("user",
        "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2",
        "M16 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0",
    ) }

    /** An agent, the author chip of an agent-made change. */
    val Code: ImageVector by lazy { build("code",
        "m16 18 6-6-6-6",
        "m8 6-6 6 6 6",
    ) }

    /** The files, the author chip of a change that arrived outside the app. */
    val Folder: ImageVector by lazy { build("folder",
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
    ) }

    /** A note, the row icon of the deleted-notes list. */
    val FileText: ImageVector by lazy { build("file-text",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8",
        "M16 13H8",
        "M16 17H8",
    ) }

    private fun build(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (d in paths) {
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
