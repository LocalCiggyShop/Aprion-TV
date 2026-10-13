package org.privateview.quietscreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.privateview.quietscreen.updates.UpdatePhase
import org.privateview.quietscreen.updates.UpdateStatus

@Composable
fun UpdateScreen(status: UpdateStatus, cancel: () -> Unit) {
    val action = remember { FocusRequester() }
    BackHandler { if (status.cancellable) cancel() }
    LaunchedEffect(status.cancellable) { if (status.cancellable) action.requestFocus() }
    Column(Modifier.fillMaxSize().background(Ink).padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Updating Aprion TV", color = Cloud, fontSize = 32.sp)
        Spacer(Modifier.height(24.dp))
        Text(status.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Cloud, fontSize = 21.sp)
        Spacer(Modifier.height(24.dp))
        if (status.phase == UpdatePhase.DOWNLOADING) {
            Box(Modifier.width(540.dp).height(8.dp).background(Panel).progressSemantics(status.fraction)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(status.fraction).background(Mint))
            }
            Spacer(Modifier.height(16.dp))
            Text("${status.sizeLabel} · ${(status.fraction * 100).toInt()}%", color = Cloud, fontSize = 19.sp)
        } else {
            val rotation by rememberInfiniteTransition(label = "Update spinner").animateFloat(
                0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "Rotation")
            Canvas(Modifier.size(40.dp).graphicsLayer { rotationZ = rotation }.progressSemantics()) {
                drawArc(Mint, 0f, 270f, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            Spacer(Modifier.height(16.dp))
            if (status.totalBytes > 0) Text("APK size: ${"%.2f".format(java.util.Locale.ROOT, status.totalBytes / 1_000_000.0)} MB", color = Muted, fontSize = 17.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(if (status.cancellable) "Playback will be available when the update finishes." else "Android may ask you to confirm. The app may close when installation finishes.", color = Muted, fontSize = 17.sp)
        if (status.cancellable) {
            Spacer(Modifier.height(24.dp))
            TvAction("Cancel update", Modifier.focusRequester(action), onClick = cancel)
        }
    }
}
