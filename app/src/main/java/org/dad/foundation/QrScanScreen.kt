package org.dad.foundation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Why the camera cannot be used right now, if it cannot. */
internal enum class ScanBlock { NONE, DENIED, PERMANENTLY_DENIED }

internal object ScanCopy {
    @StringRes val HINT_INVALID = R.string.qr_that_qr_code_isnt_an_event_check
    @StringRes val DENIED = R.string.qr_camera_access_is_needed_to_scan
    @StringRes val PERMANENTLY_DENIED = R.string.qr_camera_access_is_turned_off_for
    @StringRes val UNAVAILABLE = R.string.qr_the_camera_isnt_available_right
    @StringRes val PROMPT = R.string.qr_point_the_camera_at_the_qr_code
}

/**
 * "Scan QR code" for check-in (E-8): asks for the camera only when tapped, then shows a live viewfinder. The first
 * well-formed token is passed to [onToken] and the camera is released; codes of another shape show a hint and
 * scanning continues. "Type the code instead" is always on screen while scanning.
 */
@Composable
internal fun ScanQrSection(enabled: Boolean, onToken: (String) -> Unit, onScanStarted: () -> Unit) {
    val context = LocalContext.current
    val environment = ScannerEnvironments.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    var block by rememberSaveable { mutableStateOf(ScanBlock.NONE) }
    val launcher = rememberLauncherForActivityResult(environment.permissionContract) { granted ->
        if (granted) {
            block = ScanBlock.NONE
            scanning = true
        } else {
            block = if (environment.canAskAgain(context)) ScanBlock.DENIED else ScanBlock.PERMANENTLY_DENIED
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanning) {
            ScannerPanel(
                environment = environment,
                onToken = { token -> scanning = false; onToken(token) },
                onTypeInstead = { scanning = false },
            )
        } else {
            OutlinedButton(
                onClick = {
                    onScanStarted()
                    if (environment.isGranted(context)) { block = ScanBlock.NONE; scanning = true } else launcher.launch(android.Manifest.permission.CAMERA)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("checkin-scan"),
            ) { Text(stringResource(R.string.qr_scan_qr_code)) }
            when (block) {
                ScanBlock.NONE -> Unit
                ScanBlock.DENIED -> Text(stringResource(ScanCopy.DENIED), modifier = Modifier.testTag("scan-denied").semantics { liveRegion = LiveRegionMode.Polite })
                ScanBlock.PERMANENTLY_DENIED -> {
                    Text(stringResource(ScanCopy.PERMANENTLY_DENIED), modifier = Modifier.testTag("scan-denied-permanent").semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(
                        onClick = { context.startActivity(settingsIntent(context.packageName)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan-open-settings"),
                    ) { Text(stringResource(R.string.qr_open_settings)) }
                }
            }
        }
    }
}

private fun settingsIntent(packageName: String) =
    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun ScannerPanel(environment: ScannerEnvironment, onToken: (String) -> Unit, onTypeInstead: () -> Unit) {
    var hint by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<ScannerStatus?>(null) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    val currentOnToken by rememberUpdatedState(onToken)
    val gate = remember { ScanGate(onValid = { currentOnToken(it) }, onInvalid = { hint = ScanCopy.HINT_INVALID }) }

    // Above the viewfinder, so "Type the code instead" is on screen whenever the scanner is.
    val typeInstead: @Composable () -> Unit = {
        OutlinedButton(
            onClick = onTypeInstead,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan-type-instead"),
        ) { Text(stringResource(R.string.qr_type_the_code_instead)) }
    }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { bringIntoView.bringIntoView() }

    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).testTag("scanner-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == ScannerStatus.Unavailable) {
            Text(stringResource(ScanCopy.UNAVAILABLE), modifier = Modifier.testTag("scan-unavailable").semantics { liveRegion = LiveRegionMode.Polite })
            typeInstead()
        } else {
            Text(stringResource(ScanCopy.PROMPT), style = MaterialTheme.typography.bodyMedium)
            typeInstead()
            Box(
                Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).testTag("scanner-viewfinder")
                    .contentDescribed(R.string.qr_camera_view_for_scanning_the_event),
                contentAlignment = Alignment.Center,
            ) {
                environment.source.Viewfinder(
                    modifier = Modifier.fillMaxSize(),
                    torchOn = torchOn,
                    onDecoded = { gate.offer(it) },
                    onStatus = { status = it },
                )
                // The scan frame: a visual target, not a crop; the whole frame is analysed.
                Box(
                    Modifier.fillMaxSize(0.7f).border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(12.dp))
                        .testTag("scanner-frame"),
                )
            }
            if ((status as? ScannerStatus.Ready)?.hasTorch == true) {
                OutlinedButton(
                    onClick = { torchOn = !torchOn },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan-torch"),
                ) { Text(if (torchOn) stringResource(R.string.qr_turn_the_light_off) else stringResource(R.string.qr_turn_the_light_on)) }
            }
            hint?.let { Text(stringResource(it), modifier = Modifier.testTag("scan-hint").semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}
