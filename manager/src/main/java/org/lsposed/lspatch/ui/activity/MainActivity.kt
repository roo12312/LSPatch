@file:OptIn(
    ExperimentalMaterial3AdaptiveNavigationSuiteApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.lspatch.android.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.spec.Direction
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import com.lspatch.android.ui.navigation.TopLevelRoute
import com.lspatch.android.ui.navigation.TOP_LEVEL_DESTINATIONS
import org.matrix.vector.ui.navigation.FloatingPanelNav
import org.matrix.vector.ui.navigation.NavPanels
import org.matrix.vector.ui.navigation.PanelBar
import org.matrix.vector.ui.navigation.PanelEditDone
import org.matrix.vector.ui.navigation.TopLevelDestination
import org.matrix.vector.ui.navigation.decodeNavPanels
import org.matrix.vector.ui.navigation.encodeNavPanels
import com.lspatch.android.ui.appearance.LSPFloatingNavSettings
import com.lspatch.android.ui.page.NavGraphs
import com.lspatch.android.ui.page.appCurrentDestinationAsState
import com.lspatch.android.ui.page.destinations.Destination
import com.lspatch.android.ui.page.destinations.HomeScreenDestination
import com.lspatch.android.ui.page.destinations.LogsScreenDestination
import com.lspatch.android.ui.page.destinations.ManageScreenDestination
import com.lspatch.android.ui.page.destinations.RepoScreenDestination
import com.lspatch.android.ui.page.startAppDestination
import com.lspatch.android.ui.appearance.LSPSettings
import org.matrix.vector.ui.locale.LocalizedContent
import org.matrix.vector.ui.locale.LocalizedOverlay
import com.lspatch.android.ui.theme.LSPTheme
import com.lspatch.android.ui.util.LocalSnackbarHost
import org.matrix.vector.ui.LocalDialogLocalizer

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberAnimatedNavController()
            val themeMode by LSPSettings.themeMode.collectAsState()
            val dynamicColor by LSPSettings.dynamicColor.collectAsState()
            val seed by LSPSettings.seedColor.collectAsState()
            val amoled by LSPSettings.amoledBlack.collectAsState()
            LSPTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                seed = seed,
                amoled = amoled,
            ) {
                // The chosen language re-resolves every string below, and the localizer the shared
                // library reads is pointed at LSPatch's overlay so its sheets follow suit.
                LocalizedContent(LSPSettings) {
                    CompositionLocalProvider(
                        LocalDialogLocalizer provides { content -> LocalizedOverlay(LSPSettings, content) }
                    ) {
                val snackbarHostState = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
                    val context = LocalContext.current
                    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
                    var panels by remember {
                        mutableStateOf(
                            decodeNavPanels(prefs.getString(KEY_NAV_PANELS, "") ?: "", TOP_LEVEL_DESTINATIONS)
                        )
                    }
                    fun persist(next: NavPanels) {
                        panels = next
                        prefs.edit().putString(KEY_NAV_PANELS, encodeNavPanels(next)).apply()
                    }
                    var editing by remember { mutableStateOf(false) }

                    val currentDestination: Destination = navController.appCurrentDestinationAsState().value
                        ?: NavGraphs.root.startAppDestination
                    val currentTop = currentDestination.toTopLevelRoute()
                    val atRoot = currentTop != null

                    // One panel switch, shared by the bar and the floating navigation: everything
                    // above the start destination is popped — its state saved, so a panel comes
                    // back as it was left — and the chosen panel opens on top of it.
                    fun openPanel(destination: TopLevelDestination) {
                        val direction = destinationForKey(destination.key)
                        navController.navigate(direction.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            // The panels are siblings of the start destination rather than nested
                            // graphs, so that popUpTo files the stack it just saved under the start
                            // destination's id as well as under the panel's own. Restoring when the
                            // start destination is itself the target would therefore push the panel
                            // just left straight back, and the trip home would go nowhere.
                            restoreState = direction.route != NavGraphs.root.startAppDestination.route
                        }
                    }

                    val suiteState = rememberNavigationSuiteScaffoldState()
                    LaunchedEffect(atRoot) { if (atRoot) suiteState.show() else suiteState.hide() }
                    // Leaving a root screen also cancels an in-progress panel edit.
                    LaunchedEffect(atRoot) { if (!atRoot) editing = false }

                    val floating by LSPSettings.floatingNav.collectAsState()
                    // Floating overrules the adaptive bar/rail with None — the type that actually
                    // removes the container rather than hiding it — except while editing panels,
                    // when there has to be a bar to rearrange.
                    val suiteType =
                        if (floating && !editing) NavigationSuiteType.None
                        else NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())

                    NavigationSuiteScaffold(
                        state = suiteState,
                        navigationSuiteType = suiteType,
                        navigationItems = {
                            // Under None the NavigationSuite drops this slot with its container, so
                            // skipping it says so rather than leaving a composable that never runs.
                            if (suiteType != NavigationSuiteType.None) {
                                PanelBar(
                                    panels = panels,
                                    currentKey = currentTop?.key ?: panels.start.key,
                                    editing = editing,
                                    suiteType = suiteType,
                                    onSelect = { destination ->
                                        editing = false
                                        openPanel(destination)
                                    },
                                    onEdit = { editing = true },
                                    onToggleHidden = { key, hidden -> persist(panels.withHidden(key, hidden)) },
                                    onMove = { from, to -> persist(panels.withMoved(from, to)) },
                                )
                            }
                        },
                        primaryActionContent = {
                            if (editing) PanelEditDone(onDone = { editing = false })
                        },
                    ) {
                        // Single inset owner: each screen's own Scaffold consumes the status-bar inset
                        // (edge-to-edge), so nothing here re-applies it. The snackbar is overlaid.
                        Box(Modifier.fillMaxSize()) {
                            DestinationsNavHost(
                                navGraph = NavGraphs.root,
                                navController = navController
                            )
                            // Last child so it draws over the destination, and only at a root panel
                            // (a detail screen has its own back affordance) and not mid-edit.
                            if (floating && !editing && atRoot) {
                                FloatingPanelNav(
                                    panels = panels,
                                    currentKey = currentTop?.key ?: panels.start.key,
                                    onSelect = { destination -> openPanel(destination) },
                                    settings = LSPFloatingNavSettings,
                                )
                            }
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                            )
                        }
                    }
                }
                    }
                }
            }
        }
    }
}

private const val KEY_NAV_PANELS = "nav_panels"

/** The compose-destinations screen a top-level panel's key points at. */
private fun destinationForKey(key: String): Direction = when (key) {
    TopLevelRoute.Store.key -> RepoScreenDestination
    // Manage now carries an initial-tab arg, so it must be invoked to become a Direction; its
    // default opens the Applications tab, which is what the bar wants.
    TopLevelRoute.Manage.key -> ManageScreenDestination()
    TopLevelRoute.Logs.key -> LogsScreenDestination
    else -> HomeScreenDestination
}

/** null when the current screen is not one of the four top-level panels (e.g. New Patch). */
private fun Destination.toTopLevelRoute(): TopLevelRoute? = when (this) {
    HomeScreenDestination -> TopLevelRoute.Home
    RepoScreenDestination -> TopLevelRoute.Store
    ManageScreenDestination -> TopLevelRoute.Manage
    LogsScreenDestination -> TopLevelRoute.Logs
    else -> null
}
