package com.cccece.picharbor

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/** Short, finite Material motion; Compose supplies the system duration scale. */
object HarborMotion {
    const val SHORT=120
    const val MEDIUM=240
    fun pageOrder(page:String)=when(page){"photos"->0;"backup"->1;"me"->2;else->3}
}

/** Keep expiring cleanup evidence honest even while the results page is idle. */
@Composable
fun rememberCleanupClock():State<Long> = produceState(System.currentTimeMillis()) {
    while(true){value=System.currentTimeMillis();kotlinx.coroutines.delay(1000)}
}

@Composable
fun HarborProgress(value:Float,modifier:Modifier=Modifier) {
    val displayed by animateFloatAsState(value.coerceIn(0f,1f),tween(180,easing=FastOutSlowInEasing),label="transfer-progress")
    LinearProgressIndicator(progress={displayed},modifier=modifier)
}
