package com.vpngate.client.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpngate.client.service.ZenithVpnService
import com.vpngate.client.ui.theme.*
import kotlin.math.sin

@Composable
fun StatusCard(
    connectionState: ZenithVpnService.ConnectionState,
    serverIp: String
) {
    val shieldColor = when (connectionState) {
        ZenithVpnService.ConnectionState.CONNECTED -> ZenithConnected
        ZenithVpnService.ConnectionState.CONNECTING -> ZenithConnecting
        ZenithVpnService.ConnectionState.ERROR -> ZenithError
        ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE -> ZenithKillSwitch
        else -> ZenithOffline
    }

    val infiniteTransition = rememberInfiniteTransition(label = "waves")

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "wavePhase"
    )

    val secondaryWavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "secondaryWavePhase"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotationAngle"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center
        ) {
            if (connectionState == ZenithVpnService.ConnectionState.CONNECTED ||
                connectionState == ZenithVpnService.ConnectionState.CONNECTING) {
                
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    val midY = height / 2f
                    
                    val amp1 = 18.dp.toPx()
                    val wavePath1 = androidx.compose.ui.graphics.Path().apply {
                        for (x in 0..width.toInt() step 4) {
                            val y = amp1 * sin((2f * Math.PI * x / (width * 0.8f)) + wavePhase).toFloat() + midY
                            if (x == 0) moveTo(x.toFloat(), y) else lineTo(x.toFloat(), y)
                        }
                    }
                    drawPath(
                        path = wavePath1,
                        color = shieldColor.copy(alpha = 0.35f),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    val amp2 = 14.dp.toPx()
                    val wavePath2 = androidx.compose.ui.graphics.Path().apply {
                        for (x in 0..width.toInt() step 4) {
                            val y = amp2 * sin((2f * Math.PI * x / (width * 0.6f)) - secondaryWavePhase).toFloat() + midY
                            if (x == 0) moveTo(x.toFloat(), y) else lineTo(x.toFloat(), y)
                        }
                    }
                    drawPath(
                        path = wavePath2,
                        color = shieldColor.copy(alpha = 0.2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(shieldColor.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(196.dp)
                        .clip(CircleShape)
                        .background(shieldColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (connectionState == ZenithVpnService.ConnectionState.CONNECTING) {
                        Box(
                            modifier = Modifier
                                .size(160.dp)
                                .rotate(rotationAngle)
                                .border(
                                    width = 4.dp,
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            shieldColor.copy(alpha = 0.1f),
                                            shieldColor
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(152.dp)
                            .shadow(elevation = 3.dp, shape = CircleShape)
                            .background(Color.White, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (connectionState) {
                                ZenithVpnService.ConnectionState.CONNECTED -> Icons.Default.Shield
                                ZenithVpnService.ConnectionState.CONNECTING -> Icons.Default.HourglassEmpty
                                ZenithVpnService.ConnectionState.ERROR -> Icons.Default.Warning
                                ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE -> Icons.Default.Lock
                                else -> Icons.Default.Shield
                            },
                            contentDescription = "Shield Logo",
                            tint = shieldColor,
                            modifier = Modifier
                                .size(76.dp)
                                .graphicsLayer {
                                    if (connectionState == ZenithVpnService.ConnectionState.CONNECTING ||
                                        connectionState == ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE) {
                                        alpha = pulseAlpha
                                    }
                                }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when (connectionState) {
                ZenithVpnService.ConnectionState.CONNECTED -> "Shield Active"
                ZenithVpnService.ConnectionState.CONNECTING -> "Shield Connecting"
                ZenithVpnService.ConnectionState.ERROR -> "Shield Compromised"
                ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE -> "Traffic Blocked"
                else -> "Shield Offline"
            },
            color = ZenithTextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when (connectionState) {
                ZenithVpnService.ConnectionState.CONNECTED -> "Gateway: $serverIp"
                ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE -> "Kill switch is active — traffic blocked"
                else -> "Gateway: Not Connected"
            },
            color = ZenithTextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
