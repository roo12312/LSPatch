package com.lspatch.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import org.matrix.vector.ui.R as UiR
import org.matrix.vector.ui.navigation.TopLevelDestination

/**
 * LSPatch's four top-level panels, by stable key.
 *
 * The panels themselves — their labels, icons and order — are described by the shared
 * [TopLevelDestination] type in [TOP_LEVEL_DESTINATIONS]; this interface is kept only to map a
 * panel's key back to the compose-destinations screen it opens, which is the one thing LSPatch does
 * differently from Vector (Nav3) and so stays app-side. See MainActivity for that mapping.
 */
sealed interface TopLevelRoute {
    val key: String

    data object Home : TopLevelRoute {
        override val key = "home"
    }

    data object Store : TopLevelRoute {
        override val key = "store"
    }

    data object Manage : TopLevelRoute {
        override val key = "manage"
    }

    data object Logs : TopLevelRoute {
        override val key = "logs"
    }
}

/** The catalogue, as shared [TopLevelDestination]s — labels come from the shared library's strings. */
val TOP_LEVEL_DESTINATIONS: List<TopLevelDestination> =
    listOf(
        TopLevelDestination("home", UiR.string.nav_home, Icons.Outlined.Home, Icons.Rounded.Home),
        TopLevelDestination(
            "store",
            UiR.string.nav_store,
            Icons.Outlined.Download,
            Icons.Rounded.Download,
        ),
        TopLevelDestination(
            "manage",
            UiR.string.nav_manage,
            Icons.Outlined.Dashboard,
            Icons.Rounded.Dashboard,
        ),
        TopLevelDestination("logs", UiR.string.nav_logs, Icons.Outlined.Article, Icons.Rounded.Article),
    )
