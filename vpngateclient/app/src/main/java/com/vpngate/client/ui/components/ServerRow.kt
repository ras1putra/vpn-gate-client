package com.vpngate.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vpngate.client.R
import com.vpngate.client.model.VpnServer
import com.vpngate.client.model.ServerType
import com.vpngate.client.ui.theme.*
import java.util.Locale

fun getCountryFlagUrl(countryCode: String): String {
    return "https://flagcdn.com/w160/${countryCode.lowercase(Locale.ROOT)}.png"
}

fun getFormattedLocation(server: VpnServer): String {
    return when (server.countryShort.lowercase(Locale.ROOT)) {
        "jp" -> "Tokyo, JP"
        "kr" -> "Seoul, KR"
        "us" -> "Los Angeles, US"
        "hk" -> "Hong Kong"
        "tw" -> "Taipei, TW"
        else -> "${server.countryLong}, ${server.countryShort}"
    }
}

@Composable
fun ServerRow(
    server: VpnServer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onConnectClick: () -> Unit
) {
    val cardBgColor = if (isSelected) {
        ZenithActiveCardBg
    } else {
        when (server.serverType) {
            ServerType.ACADEMIC -> ZenithAcademicBg
            ServerType.DATACENTER -> ZenithDatacenterBg
            ServerType.RESIDENTIAL -> Color.White
        }
    }

    val cardBorder = androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = if (isSelected) {
            ZenithTeal
        } else {
            when (server.serverType) {
                ServerType.ACADEMIC -> ZenithAcademicBorder
                ServerType.DATACENTER -> ZenithDatacenterBorder
                ServerType.RESIDENTIAL -> Color.Transparent
            }
        }
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { 
                onSelect() 
                onConnectClick()
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = cardBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    AsyncImage(
                        model = getCountryFlagUrl(server.countryShort),
                        placeholder = painterResource(id = R.drawable.flag_un),
                        error = painterResource(id = R.drawable.flag_un),
                        contentDescription = "Country Flag",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(1.dp, ZenithBorder, CircleShape)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = getFormattedLocation(server),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = ZenithTextPrimary
                            )
                            
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Connected indicator",
                                        tint = ZenithStealthText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Connected",
                                        color = ZenithStealthText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${server.method} (${server.ip})",
                            fontSize = 14.sp,
                            color = ZenithTextSecondaryAlt
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ping: ${server.ping}ms  •  Speed: ${server.speedMbs.toInt()} Mbps",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ZenithTextMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Uptime: ${server.formattedUptime}  •  Score: ${server.score}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = ZenithTextSecondaryAlt
                    )
                }

                val badgeText: String
                val badgeBg: Color
                val badgeTextCol: Color
                when (server.serverType) {
                    ServerType.RESIDENTIAL -> {
                        badgeText = "Stealth"
                        badgeBg = ZenithStealthBg
                        badgeTextCol = ZenithStealthText
                    }
                    ServerType.ACADEMIC -> {
                        badgeText = "Academic"
                        badgeBg = ZenithAcademicBg
                        badgeTextCol = ZenithAcademicText
                    }
                    ServerType.DATACENTER -> {
                        badgeText = "Datacenter"
                        badgeBg = ZenithDatacenterBg
                        badgeTextCol = ZenithDatacenterText
                    }
                }

                Box(
                    modifier = Modifier
                        .background(color = badgeBg, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeTextCol,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
