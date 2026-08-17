package com.lspatch.android.ui.page.manage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import com.lspatch.android.R
import com.lspatch.android.data.model.PatchStep
import com.lspatch.android.data.repository.PatchJobHost
import com.lspatch.android.lspApp
import com.lspatch.android.share.Constants
import com.lspatch.android.share.LSPConfig
import com.lspatch.android.ui.component.PatchProgressLine
import com.lspatch.android.ui.page.destinations.AppDetailScreenDestination
import com.lspatch.android.ui.page.destinations.NewPatchScreenDestination
import com.lspatch.android.ui.page.startNewPatch
import com.lspatch.android.ui.util.LocalSnackbarHost
import com.lspatch.android.ui.viewmodel.manage.AppManageViewModel
import com.lspatch.android.ui.viewstate.ProcessingState
import com.lspatch.android.util.LSPPackageManager
import com.lspatch.android.util.ShizukuApi
import org.matrix.vector.ui.ApiBadge
import org.matrix.vector.ui.ModuleRow
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.REACH_ICON_SIZE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManageBody(navigator: DestinationsNavigator, query: String) {
    val viewModel = viewModel<AppManageViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var refreshing by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<LSPPackageManager.AppInfo?>(null) }
    val step by PatchJobHost.step.collectAsStateWithLifecycle()

    when (val state = viewModel.optimizeState) {
        is ProcessingState.Idle, is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val optimizeSucceed = stringResource(R.string.manage_optimize_successfully)
            val optimizeFailed = stringResource(R.string.manage_optimize_failed)
            val forceStop = stringResource(R.string.manage_forcestop)
            val stopped = stringResource(R.string.manage_forcestop_done)
            LaunchedEffect(state) {
                // Recompiling does not touch a process that is already running: it holds the code it
                // was started with, so the newly-uncompiled methods only matter next launch. The
                // restart is offered rather than done, because force-stopping an app the user is in
                // the middle of using is not a side effect to spring on them.
                val target = viewModel.lastOptimized
                val result = snackbarHost.showSnackbar(
                    message = if (state.result) optimizeSucceed else optimizeFailed,
                    actionLabel = if (state.result && target != null) forceStop else null,
                )
                if (result == SnackbarResult.ActionPerformed && target != null) {
                    ShizukuApi.runShellCommand("am force-stop $target")
                    snackbarHost.showSnackbar(stopped)
                }
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearOptimizeResult)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // A patch started here and then walked away from is still running; without this the only
        // evidence of it would be the app appearing, eventually, with no explanation.
        PatchProgressLine(
            step = step,
            onClick = {
                val active = PatchJobHost.active.value
                if (active != null) navigator.navigate(NewPatchScreenDestination(token = active.token))
                else PatchJobHost.acknowledge()
            },
        )

        if (viewModel.appList.isEmpty()) {
            val loading = LSPPackageManager.appList.isEmpty()
            PanelEmptyState(
                icon = if (loading) Icons.Rounded.HourglassEmpty else Icons.Rounded.Dashboard,
                text = stringResource(if (loading) R.string.manage_loading else R.string.manage_no_apps),
            )
            return@Column
        }

        val shown = viewModel.appList.filter {
            query.isBlank() ||
                it.first.label.contains(query, true) ||
                it.first.app.packageName.contains(query, true)
        }
        if (shown.isEmpty()) {
            PanelEmptyState(
                icon = Icons.Rounded.SearchOff,
                text = stringResource(R.string.manage_no_match),
            )
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    LSPPackageManager.fetchAppList()
                    refreshing = false
                }
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(items = shown, key = { it.first.app.packageName }) { item ->
                    val (appInfo, config) = item
                    val local = config.useManager
                    // The loader version is unified on the commit count. A rolling manager-backed loader
                    // has no fixed commit to show, so it reads "Rolling"; anything older than this build's
                    // loader carries the update mark and is upgraded from the detail page.
                    val isRolling = local && config.lspConfig.VERSION_CODE >= Constants.MIN_ROLLING_VERSION_CODE
                    val hasUpdate = !isRolling && config.lspConfig.VERSION_CODE < LSPConfig.instance.VERSION_CODE
                    val loaderValue =
                        if (isRolling) stringResource(R.string.manage_rolling)
                        else config.lspConfig.VERSION_CODE.toString()
                    val appVersion = remember(appInfo.app.packageName) {
                        runCatching {
                            lspApp.packageManager.getPackageInfo(appInfo.app.packageName, 0).versionName
                        }.getOrNull().orEmpty()
                    }
                    val moduleIcons = viewModel.moduleIcons[appInfo.app.packageName].orEmpty()
                    ModuleRow(
                        name = appInfo.label,
                        versionName = appVersion,
                        description = "",
                        icon = {
                            Icon(
                                bitmap = LSPPackageManager.getIcon(appInfo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        apiBadge = { ApiBadge(label = "Loader", value = loaderValue) },
                        hasUpdate = hasUpdate,
                        // Tap opens the page; long press opens the quick actions. The icon is the
                        // same target as the row, but carries no long press of its own -- it is a
                        // selection handle everywhere else, and a second gesture on it would not be
                        // discoverable.
                        onClick = {
                            navigator.navigate(AppDetailScreenDestination(packageName = appInfo.app.packageName))
                        },
                        onIconClick = {
                            navigator.navigate(AppDetailScreenDestination(packageName = appInfo.app.packageName))
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sheetFor = appInfo
                        },
                        // Apps carry no Xposed description; the note slot stands in with the package name
                        // alone, on its own full-width line.
                        note = {
                            Text(
                                text = appInfo.app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // The patch mode sits at the far left of the bottom reach band, opposite the
                        // module icons on the right — Local (manager-backed, dynamic scope) vs
                        // Integrated (modules baked in), the one distinction that changes how the app
                        // is managed. On the band rather than a line of its own, so the row is compact.
                        reachStart = {
                            val modeColor =
                                if (local) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.tertiary
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(modeColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = stringResource(if (local) R.string.patch_local else R.string.patch_integrated),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = modeColor,
                                )
                            }
                        },
                        // The modules this app reaches, as thumbnails then "+N". Handed to the row as data —
                        // the row draws it bottom-right itself, the same corner a module's scope lands in,
                        // so neither side is positioned by hand here.
                        reachIcons = moduleIcons.map { bitmap ->
                            {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(REACH_ICON_SIZE),
                                )
                            }
                        },
                        reachCount = moduleIcons.size,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    sheetFor?.let { app ->
        AppActionSheet(app = app, onDismiss = { sheetFor = null })
    }
}

/**
 * Starts a patch -- the same call Home's button makes.
 *
 * It used to demand a storage folder before it would do anything, take a persistable permission on
 * it, and then offer a choice between an app and a file. Home did none of that, which is precisely
 * why a patch begun there had nowhere to write and failed every time.
 */
@Composable
fun AppManageFab(navigator: DestinationsNavigator) {
    FloatingActionButton(onClick = { startNewPatch(navigator) }) {
        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add))
    }
}
