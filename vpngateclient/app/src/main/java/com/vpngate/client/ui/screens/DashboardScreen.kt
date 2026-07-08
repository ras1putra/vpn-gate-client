package com.vpngate.client.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpngate.client.model.VpnServer
import com.vpngate.client.data.ServerRepository
import com.vpngate.client.service.VpnManager
import com.vpngate.client.service.ZenithVpnService
import com.vpngate.client.ui.components.*
import com.vpngate.client.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    SERVER_SELECTION
}

data class CountryTabItem(val name: String, val code: String)

@Composable
fun VpnDashboard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

    val connectionState by ZenithVpnService.connectionState.collectAsState()
    val connectedServerIp by ZenithVpnService.connectedServerIp.collectAsState()

    var isScraping by remember { mutableStateOf(false) }
    var serversList by remember { mutableStateOf(emptyList<VpnServer>()) }
    var selectedServer by remember { mutableStateOf<VpnServer?>(null) }
    var showOnlyResidential by remember { mutableStateOf(true) }
    var selectedCountryTab by remember { mutableStateOf("All") }
    var dataSource by remember { mutableStateOf("loading") }

    var failoverCandidates by remember { mutableStateOf(emptyList<VpnServer>()) }
    var nextFailoverIndex by remember { mutableStateOf(0) }
    var showFailoverFailedDialog by remember { mutableStateOf(false) }

    var isKillSwitchEnabled by remember { mutableStateOf(false) }

    var hasAcceptedAdvisory by remember { mutableStateOf(true) }

    var isNotificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        android.util.Log.d("VpnDashboard", "notificationPermissionLauncher: isGranted = $isGranted")
        isNotificationPermissionGranted = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Notification permission is required to run Zenith VPN", Toast.LENGTH_LONG).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                    isNotificationPermissionGranted = permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        VpnManager.initialize(context)

        val prefs = context.getSharedPreferences("zenith_prefs", android.content.Context.MODE_PRIVATE)
        hasAcceptedAdvisory = prefs.getBoolean("accepted_advisory", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            isNotificationPermissionGranted = permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!isNotificationPermissionGranted) {
                android.util.Log.d("VpnDashboard", "Requesting runtime POST_NOTIFICATIONS permission...")
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var previousState by remember { mutableStateOf(connectionState) }
    LaunchedEffect(connectionState) {
        if (previousState == ZenithVpnService.ConnectionState.CONNECTING) {
            if (connectionState == ZenithVpnService.ConnectionState.CONNECTED) {
                Toast.makeText(context, "Zenith VPN Connected!", Toast.LENGTH_SHORT).show()
                failoverCandidates = emptyList()
            } else if (connectionState == ZenithVpnService.ConnectionState.ERROR) {
                if (failoverCandidates.isNotEmpty() && nextFailoverIndex < failoverCandidates.size) {
                    val nextServer = failoverCandidates[nextFailoverIndex]
                    nextFailoverIndex++
                    selectedServer = nextServer
                    Toast.makeText(
                        context,
                        "Retrying with another server from ${nextServer.countryLong} (${nextFailoverIndex}/${failoverCandidates.size})...",
                        Toast.LENGTH_LONG
                    ).show()
                    VpnManager.connect(context, nextServer)
                } else {
                    showFailoverFailedDialog = true
                    Toast.makeText(context, "All connection attempts failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
        previousState = connectionState
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedServer?.let { server ->
                Toast.makeText(context, "Connecting to ${getFormattedLocation(server)}...", Toast.LENGTH_SHORT).show()
                currentScreen = AppScreen.HOME
                VpnManager.connect(context, server)
            }
        } else {
            Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    val onConnectClick: (VpnServer) -> Unit = { server ->
        selectedServer = server
        failoverCandidates = serversList.filter {
            it.countryShort.lowercase(Locale.ROOT) == server.countryShort.lowercase(Locale.ROOT) &&
            it.ip != server.ip
        }
        nextFailoverIndex = 0
        showFailoverFailedDialog = false

        val prepareIntent = VpnManager.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            Toast.makeText(context, "Connecting to ${getFormattedLocation(server)}...", Toast.LENGTH_SHORT).show()
            currentScreen = AppScreen.HOME
            VpnManager.connect(context, server)
        }
    }

    val serverRepository = remember { ServerRepository(context) }

    val fetchServers: suspend () -> Unit = {
        isScraping = true
        val result = serverRepository.getServers()
        result.onSuccess { servers ->
            serversList = servers
            dataSource = "ok"
        }.onFailure {
            dataSource = "error"
        }
        isScraping = false
    }

    LaunchedEffect(Unit) {
        fetchServers()
    }

    val filteredServers = remember(serversList, showOnlyResidential) {
        if (showOnlyResidential) {
            serversList.filter { it.isStealth || it.vpnDetected == null }
        } else {
            serversList
        }
    }

    val countryTabs = remember(filteredServers) {
        val list = mutableListOf(CountryTabItem("All", "ALL"))
        val uniqueCountries = filteredServers.map { it.countryLong }.distinct().sorted()
        for (c in uniqueCountries) {
            val code = filteredServers.firstOrNull { it.countryLong == c }?.countryShort ?: ""
            list.add(CountryTabItem(c, code))
        }
        list
    }

    val stableSelectedCountryTab = remember(countryTabs, selectedCountryTab) {
        if (countryTabs.none { it.name == selectedCountryTab }) "All" else selectedCountryTab
    }
    LaunchedEffect(stableSelectedCountryTab) {
        if (stableSelectedCountryTab != selectedCountryTab) {
            selectedCountryTab = stableSelectedCountryTab
        }
    }

    val displayServers = remember(filteredServers, stableSelectedCountryTab) {
        if (stableSelectedCountryTab == "All") {
            filteredServers
        } else {
            filteredServers.filter { it.countryLong == stableSelectedCountryTab }
        }
    }

    if (currentScreen == AppScreen.SERVER_SELECTION) {
        BackHandler {
            currentScreen = AppScreen.HOME
        }
    }

    val backgroundGradient = Brush.linearGradient(
        colors = listOf(
            ZenithBackgroundStart,
            ZenithBackgroundEnd
        )
    )

    if (!isNotificationPermissionGranted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Notification Required",
                    tint = ZenithTeal,
                    modifier = Modifier.size(72.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Notification Required",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTextPrimary,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Zenith VPN requires notification permissions to display active connection status, speeds, and quick disconnect controls.\n\nYou cannot access the application without allowing notifications.",
                    fontSize = 14.sp,
                    color = ZenithTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTeal)
                ) {
                    Text(
                        text = "Grant Permission",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                TextButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                ) {
                    Text(
                        text = "Open System Settings",
                        color = ZenithTeal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentScreen == AppScreen.HOME) {
                HomeScreen(
                    connectionState = connectionState,
                    connectedServerIp = connectedServerIp,
                    isKillSwitchEnabled = isKillSwitchEnabled,
                    onKillSwitchToggle = { enabled ->
                        isKillSwitchEnabled = enabled
                        try {
                            val intent = Intent("android.net.vpn.SETTINGS")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    onSelectServerClick = { currentScreen = AppScreen.SERVER_SELECTION }
                )
            } else {
                ServerSelectionScreen(
                    connectionState = connectionState,
                    connectedServerIp = connectedServerIp,
                    isScraping = isScraping,
                    countryTabs = countryTabs,
                    selectedCountryTab = selectedCountryTab,
                    onTabSelect = { selectedCountryTab = it },
                    showOnlyResidential = showOnlyResidential,
                    onShowOnlyResidentialChange = { showOnlyResidential = it },
                    displayServers = displayServers,
                    selectedServer = selectedServer,
                    onServerSelect = { selectedServer = it },
                    onConnectClick = onConnectClick,
                    onBackClick = { currentScreen = AppScreen.HOME },
                    onRefreshClick = {
                        coroutineScope.launch {
                            fetchServers()
                        }
                    }
                )
            }
        }
    }

    if (showFailoverFailedDialog) {
        AlertDialog(
            onDismissRequest = { showFailoverFailedDialog = false },
            title = {
                Text(
                    text = "Connection Failed",
                    fontWeight = FontWeight.Bold,
                    color = ZenithTextDark
                )
            },
            text = {
                Text(
                    text = "We tried all available servers in ${selectedServer?.countryLong ?: "the selected country"}, but none of them could establish a connection. " +
                           "Would you like to disconnect safely or pick a different country?",
                    fontSize = 14.sp,
                    color = ZenithTextSecondaryAlt
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFailoverFailedDialog = false
                        VpnManager.disconnect(context)
                    }
                ) {
                    Text("Disconnect Safely", color = ZenithError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showFailoverFailedDialog = false 
                    }
                ) {
                    Text("Choose Another", color = ZenithTeal)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (!hasAcceptedAdvisory) {
        var isChecked by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { /* Force acceptance on first run */ },
            title = {
                Text(
                    text = "Security Advisory & Terms",
                    fontWeight = FontWeight.Bold,
                    color = ZenithTextDark
                )
            },
            text = {
                Column {
                    Text(
                        text = "Please review the following security warnings before using Zenith VPN:",
                        fontSize = 14.sp,
                        color = ZenithTextDark,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    val bulletPoints = listOf(
                        "This app aggregates free servers hosted by public volunteers.",
                        "Avoid HTTP (unencrypted) websites. Malicious relay operators can inspect traffic and capture passwords or sensitive data.",
                        "Always ensure you only access secure HTTPS websites showing a padlock icon."
                    )
                    
                    bulletPoints.forEach { point ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = ZenithTeal,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text(
                                text = point,
                                fontSize = 12.sp,
                                color = ZenithTextSecondaryAlt,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isChecked = !isChecked }
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { isChecked = it },
                            colors = CheckboxDefaults.colors(checkedColor = ZenithTeal)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "I understand the risks and accept the terms",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = ZenithTextDark
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val prefs = context.getSharedPreferences("zenith_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("accepted_advisory", true).apply()
                        hasAcceptedAdvisory = true
                    },
                    enabled = isChecked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ZenithTeal,
                        contentColor = Color.White,
                        disabledContainerColor = ZenithBorderLight,
                        disabledContentColor = ZenithTextSecondary
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Accept & Continue", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun HomeScreen(
    connectionState: ZenithVpnService.ConnectionState,
    connectedServerIp: String?,
    isKillSwitchEnabled: Boolean,
    onKillSwitchToggle: (Boolean) -> Unit,
    onSelectServerClick: () -> Unit
) {
    val context = LocalContext.current
    var showKillSwitchInstructionsDialog by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAlwaysOnDisconnectWarning by remember { androidx.compose.runtime.mutableStateOf(false) }

    val settingsVpnPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                context.startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to open VPN settings", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(context, "VPN Permission is required to configure the Kill Switch.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(color = ZenithTextDark)) {
                    append("ZENITH ")
                }
                withStyle(style = SpanStyle(color = ZenithTeal)) {
                    append("VPN")
                }
            },
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        StatusCard(
            connectionState = connectionState,
            serverIp = connectedServerIp ?: "Not Connected"
        )

        Spacer(modifier = Modifier.weight(1.2f))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showKillSwitchInstructionsDialog = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, ZenithBorderLight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "System Kill Switch",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = ZenithTextDark
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Configure Always-on & network lockdown",
                        fontSize = 13.sp,
                        color = ZenithTextSecondaryAlt
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configure settings",
                    tint = ZenithTeal,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        if (connectionState == ZenithVpnService.ConnectionState.CONNECTED ||
            connectionState == ZenithVpnService.ConnectionState.CONNECTING) {
            Button(
                onClick = {
                    val alwaysOnApp = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        "always_on_vpn_app"
                    )
                    if (alwaysOnApp == context.packageName) {
                        showAlwaysOnDisconnectWarning = true
                    } else {
                        android.util.Log.i("VpnDashboard", "Disconnect clicked: terminating ZenithVpnService...")
                        VpnManager.disconnect(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZenithError,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "Disconnect",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = onSelectServerClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ZenithTeal,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Select Server",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showKillSwitchInstructionsDialog) {
        AlertDialog(
            onDismissRequest = { showKillSwitchInstructionsDialog = false },
            title = {
                Text(
                    text = "System Kill Switch",
                    fontWeight = FontWeight.Bold,
                    color = ZenithTextDark
                )
            },
            text = {
                Text(
                    text = "To enable the Kill Switch:\n\n" +
                           "1. Tap 'Go to Settings' below.\n" +
                           "2. Click the gear icon next to Zenith VPN.\n" +
                           "3. Toggle on 'Always-on VPN'.\n" +
                           "4. Toggle on 'Block connections without VPN'.",
                    fontSize = 14.sp,
                    color = ZenithTextSecondaryAlt
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKillSwitchInstructionsDialog = false
                        val prepareIntent = android.net.VpnService.prepare(context)
                        if (prepareIntent != null) {
                            settingsVpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Failed to open VPN settings", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Go to Settings", color = ZenithTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showKillSwitchInstructionsDialog = false }) {
                    Text("Cancel", color = ZenithTextSecondary)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showAlwaysOnDisconnectWarning) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnDisconnectWarning = false },
            title = {
                Text(
                    text = "Always-on VPN active",
                    fontWeight = FontWeight.Bold,
                    color = ZenithTextDark
                )
            },
            text = {
                Text(
                    text = "Always-on VPN Lockdown is active in your device settings. Android forces the connection to stay active, preventing the app from disconnecting.\n\n" +
                           "To disconnect, please tap 'Go to Settings' below, click the gear next to Zenith VPN, and turn off 'Always-on VPN' or 'Block connections without VPN'.",
                    fontSize = 14.sp,
                    color = ZenithTextSecondaryAlt
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlwaysOnDisconnectWarning = false
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Failed to open VPN settings", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Go to Settings", color = ZenithTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlwaysOnDisconnectWarning = false }) {
                    Text("Cancel", color = ZenithTextSecondary)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun ServerSelectionScreen(
    connectionState: ZenithVpnService.ConnectionState,
    connectedServerIp: String?,
    isScraping: Boolean,
    countryTabs: List<CountryTabItem>,
    selectedCountryTab: String,
    onTabSelect: (String) -> Unit,
    showOnlyResidential: Boolean,
    onShowOnlyResidentialChange: (Boolean) -> Unit,
    displayServers: List<VpnServer>,
    selectedServer: VpnServer?,
    onServerSelect: (VpnServer) -> Unit,
    onConnectClick: (VpnServer) -> Unit,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    val context = LocalContext.current
    val tabScrollState = androidx.compose.foundation.rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onBackClick() }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ZenithTeal,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Back",
                    color = ZenithTeal,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Text(
                text = "VPN Server List",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ZenithTextPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (countryTabs.size > 1 && !isScraping) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(tabScrollState)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    countryTabs.forEachIndexed { index, tab ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selectedCountryTab == tab.name) ZenithTabSelected else Color.Transparent)
                                .clickable { onTabSelect(tab.name) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.code.uppercase(Locale.ROOT),
                                color = if (selectedCountryTab == tab.name) Color.White else ZenithTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (index < countryTabs.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(16.dp)
                                    .background(ZenithDivider)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Available Relays (${displayServers.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = ZenithTextPrimary
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, ZenithDivider.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowOnlyResidentialChange(!showOnlyResidential) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Stealth Filter",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = ZenithTextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Filter to IPs that are not detected as a VPN",
                        fontSize = 13.sp,
                        color = ZenithTextSecondary
                    )
                }

                Switch(
                    checked = showOnlyResidential,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ZenithTeal,
                        uncheckedThumbColor = ZenithSwitchThumbUnchecked,
                        uncheckedTrackColor = ZenithDivider
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isScraping) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ZenithTeal)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayServers) { server ->
                    val isConnected = connectionState == ZenithVpnService.ConnectionState.CONNECTED && server.ip == connectedServerIp
                    ServerRow(
                        server = server,
                        isSelected = isConnected,
                        onSelect = { onServerSelect(server) },
                        onConnectClick = {
                            onServerSelect(server)
                            onConnectClick(server)
                        }
                    )
                }

                if (displayServers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching relays found.\nTap refresh below.",
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                                color = ZenithTextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onRefreshClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ZenithTeal,
                contentColor = Color.White
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "REFRESH",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
