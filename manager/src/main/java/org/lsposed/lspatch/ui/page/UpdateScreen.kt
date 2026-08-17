package com.lspatch.android.ui.page

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.lspatch.android.R
import com.lspatch.android.lspApp
import com.lspatch.android.share.LSPConfig
import com.lspatch.android.ui.viewmodel.UpdateViewModel
import org.matrix.vector.ui.store.StoreHtmlPane
import org.matrix.vector.ui.store.releaseMarkdownToHtml
import org.matrix.vector.ui.update.VariantChoice
import org.matrix.vector.ui.update.VariantPicker

/**
 * The full-screen self-update page, modelled on Vector's `FrameworkUpdateScreen` but for the
 * manager apk: the top bar names the build and channel, the body renders the release notes through
 * the shared `StoreHtmlPane`, and the bottom bar carries the whole download-and-install act. It is
 * reachable from the version line whether or not an update exists, so "up to date" and a re-check
 * are always in reach — the same contract the old sheet held, now given the room the notes need.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun UpdateScreen(navigator: DestinationsNavigator) {
    val vm = viewModel<UpdateViewModel>()
    val update = vm.update
    val stage = vm.updateStage
    val checking = vm.checkingUpdate
    val context = LocalContext.current

    // Debug when the running build was assembled debuggable, which is the variant it will self-update
    // from — the same signal that picks manager-debug.apk over manager.apk.
    val debuggable = (lspApp.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LSPatch " + (update?.version ?: "v${LSPConfig.instance.VERSION_NAME}"),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (debuggable) R.string.update_channel_debug
                                    else R.string.update_channel_release
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.update_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { openUrl(update?.url ?: "${UpdateViewModel.REPO_URL}/releases") }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.update_open_release),
                        )
                    }
                },
            )
        },
        bottomBar = {
            UpdateBar(
                // The page always holds the latest release now (to show its notes), so an update is
                // on offer only when that release is actually newer than this build.
                hasUpdate = update?.newer == true,
                hasApk = vm.chosenApk != null,
                // The latest was fetched and it is not newer -- the reader is on the newest build.
                upToDate = update != null && update.newer == false,
                checking = checking,
                stage = stage,
                // The Release/Debug choice, driven by the shared manager-ui picker.
                variantChoices =
                    update?.apks?.map { VariantChoice(it.key, it.sizeInBytes, it.name) }.orEmpty(),
                selectedVariant = vm.chosenVariant,
                onSelectVariant = vm::chooseVariant,
                onInstall = { vm.downloadAndInstall() },
                onOpenReleases = { openUrl(update?.url ?: "${UpdateViewModel.REPO_URL}/releases") },
                onCheck = { vm.checkUpdate() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val html =
                remember(update?.notes) {
                    update?.notes?.takeIf { it.isNotBlank() }?.let {
                        releaseMarkdownToHtml(it, "https://github.com/JingMatrix/LSPatch")
                    }
                }
            when {
                // Notes render whenever the latest release carries a body -- even up to date, which
                // is the point: the page shows the newest release's notes, not a blank "up to date".
                html != null ->
                    StoreHtmlPane(
                        html = html,
                        modifier = Modifier.fillMaxSize(),
                        onOpenUrl = openUrl,
                        fetchSubresource = null,
                        contextForWebView = null,
                    )
                // Still fetching and nothing loaded yet.
                checking && update == null -> Empty(stringResource(R.string.update_checking))
                // Fetched, but the release has no notes body.
                update != null -> Empty(stringResource(R.string.update_no_notes))
                // The check failed (network/parse) -- honest about it rather than claiming up to date.
                else -> Empty(stringResource(R.string.update_check_failed))
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The bottom install bar, mirroring Vector's `UpdateBar` shape: a running download or install shows
 * progress and its label, a failure offers a retry, and while idle the primary action is either
 * download-and-install (when a release is in hand) or a re-check.
 */
@Composable
private fun UpdateBar(
    hasUpdate: Boolean,
    hasApk: Boolean,
    upToDate: Boolean,
    checking: Boolean,
    stage: UpdateViewModel.UpdateStage,
    variantChoices: List<VariantChoice>,
    selectedVariant: String,
    onSelectVariant: (String) -> Unit,
    onInstall: () -> Unit,
    onOpenReleases: () -> Unit,
    onCheck: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when (stage) {
            is UpdateViewModel.UpdateStage.Downloading -> {
                if (stage.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { stage.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.update_downloading),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            is UpdateViewModel.UpdateStage.Installing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.update_installing),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            is UpdateViewModel.UpdateStage.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stage.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onInstall) { Text(stringResource(R.string.update_retry)) }
                }
            UpdateViewModel.UpdateStage.Idle ->
                Column {
                    // The Release/Debug choice sits above the button it answers, whenever there is
                    // an apk to install. The shared picker draws nothing when the release published
                    // only one variant, so this costs a blank row in no case.
                    if (hasApk) {
                        VariantPicker(
                            choices = variantChoices,
                            selectedKey = selectedVariant,
                            onSelect = onSelectVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (hasUpdate) {
                        Button(
                            // No apk asset on the release means there is nothing to self-install; the
                            // browser is the only way forward, so the button opens the release page.
                            onClick = { if (hasApk) onInstall() else onOpenReleases() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (hasApk) R.string.update_install
                                    else R.string.update_open_release
                                )
                            )
                        }
                    } else if (upToDate) {
                    // On the newest build: say so, keep a re-check within reach, and -- as Vector
                    // does -- still offer to install the matching apk, since a reinstall of the same
                    // build is a legitimate want (repair, or re-apply the current release).
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.update_up_to_date),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onCheck, enabled = !checking) {
                                Text(stringResource(R.string.update_check))
                            }
                        }
                        if (hasApk) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_reinstall))
                            }
                        }
                    }
                    } else {
                        Button(
                            onClick = onCheck,
                            enabled = !checking,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.update_check))
                        }
                    }
                }
        }
    }
}
