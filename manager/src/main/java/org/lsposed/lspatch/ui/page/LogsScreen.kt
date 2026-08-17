package com.lspatch.android.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.lspatch.android.R
import com.lspatch.android.data.repository.LSPLogSource
import com.lspatch.android.ui.page.destinations.LogTraceScreenDestination
import com.lspatch.android.util.ShizukuApi
import org.matrix.vector.ui.logs.LogsScreen as SharedLogsScreen
import rikka.shizuku.Shizuku

/**
 * The Logs page. A thin host over the shared `org.matrix.vector.ui.logs.LogsScreen`.
 *
 * LSPatch reads the device log through the Shizuku shell rather than a root daemon, so this gates
 * the shared screen behind the Shizuku permission and, once granted, hands it an [LSPLogSource].
 * The shared screen then supplies the whole surface — the level-coloured rows, the tag/level filter
 * sheet, the day breaks, the search and jump-to-newest — driven by the snapshot the source reads.
 * A trace opens in place or, when the reader turns that setting off, on the shared trace screen this
 * navigates to.
 */
@Destination
@Composable
fun LogsScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val granted = ShizukuApi.isPermissionGranted

    if (!granted) {
        Scaffold { innerPadding ->
            ShizukuPrompt(Modifier.padding(innerPadding))
        }
        return
    }

    val source = remember { LSPLogSource(context.applicationContext) }
    SharedLogsScreen(
        source = source,
        onOpenTrace = { text -> navigator.navigate(LogTraceScreenDestination(text = text)) },
    )
}

@Composable
private fun ShizukuPrompt(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.CloudOff,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.logs_state_shizuku_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.logs_state_shizuku_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = { if (ShizukuApi.isBinderAvailable) Shizuku.requestPermission(114514) }
        ) {
            Text(stringResource(R.string.logs_grant))
        }
    }
}
