package app.clearspace.network.discovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import app.clearspace.network.MainActivity
import android.content.ServiceConnection
import android.content.ComponentName
import app.clearspace.network.IAetherIpc
import app.clearspace.network.storage.AetherDatabaseService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.clearspace.network.network.webrtc.WebRtcSwarmAgent
import app.clearspace.network.network.WebRtcManager
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.discovery.ContentHeader

class DiscoveryForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ClearSpace_sharing_channel"
        private const val NOTIFICATION_ID = 101
        const val ACTION_STOP_SHARING = "app.clearspace.network.ACTION_STOP_SHARING"
    }

    private var discoveryManager: DiscoveryManager? = null
    private var bleDiscovery: BleDiscovery? = null
    private var p2pServer: P2PServer? = null
    private val ephemeralPeerId = java.util.UUID.randomUUID().toString().substring(0, 8)
    
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    bleDiscovery?.setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_OPPORTUNISTIC)
                }
                Intent.ACTION_SCREEN_ON -> {
                    bleDiscovery?.setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
                }
            }
        }
    }

    private var ipcService: IAetherIpc? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ipcService = IAetherIpc.Stub.asInterface(service)
            startServerAndDiscovery()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            ipcService = null
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Initialize discovery manager with NSD, BLE, and Wi-Fi Direct
        val nsdDiscovery = NsdDiscovery(this)
        bleDiscovery = BleDiscovery(this)
        val wifiDirectDiscovery = WifiDirectDiscovery(this)
        discoveryManager = DiscoveryManager(listOf(nsdDiscovery, bleDiscovery!!, wifiDirectDiscovery))
        
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            discoveryManager?.stop()
            p2pServer?.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (ex2: Exception) {
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (ex3: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val intentIpc = Intent(this, AetherDatabaseService::class.java)
        bindService(intentIpc, connection, Context.BIND_AUTO_CREATE)

        return START_STICKY
    }

    private fun startServerAndDiscovery() {
        val ipc = ipcService ?: return

        app.clearspace.network.security.guard.SecureP2PManager.init(this)
        // FIX C3 + L3: Initialize P2PClient with app context for signature verification and security logging
        P2PClient.init(this)
        p2pServer = P2PServer(this, ipc)
        val serverPort = p2pServer?.start() ?: 0

        val myHashedId = ipc.myHashedId
        val hasProfile = myHashedId.isNotEmpty()

        val packet = PresencePacket(
            peerId = ephemeralPeerId,
            hasProfile = hasProfile,
            categoryBitmask = 0L,
            tcpPort = serverPort
        )
        discoveryManager?.start(packet)

        val coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        coroutineScope.launch {
            discoveryManager?.discoveredPeers?.collect { peers ->
                val sampledPeers = peers.shuffled().take(3)
                sampledPeers.forEach { peer ->
                    val peerIp = peer.ip
                    if (peerIp != null && peer.tcpPort > 0) {
                        try {
                            val prefs = getSharedPreferences("aether_settings", Context.MODE_PRIVATE)
                            val quotaMb = prefs.getInt("relay_quota_mb", 500)
                            val maxBytes = quotaMb * 1024L * 1024L
                            
                            val packetIds = P2PClient.fetchRelayIndex(peerIp, peer.tcpPort) ?: emptyList()
                            val currentUsage = ipc.relayUsage
                            
                            val currentTime = System.currentTimeMillis()
                            for (id in packetIds) {
                                val exists = ipc.hasRelayPacket(currentTime, id)
                                if (!exists) {
                                    val fetched = P2PClient.fetchRelayPacket(peerIp, peer.tcpPort, id)
                                    if (fetched != null) {
                                        val isForMe = (fetched.hashedRecipientId == myHashedId)
                                        if (isForMe || currentUsage < maxBytes) {
                                            ipc.insertRelayPacket(Json.encodeToString(fetched))
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Suppress logs
                        }
                    }
                }
            }
        }

        coroutineScope.launch {
            while (isActive) {
                try {
                    ipc.enforceStorageQuota()
                } catch (e: Exception) {}
                kotlinx.coroutines.delay(5 * 60 * 1000L) // Check every 5 minutes
            }
        }

        // WebRTC Global Swarm configuration
        val webRtcManager = WebRtcManager.getInstance(this)
        webRtcManager.onDataReceived = { message ->
            coroutineScope.launch {
                try {
                    if (message == "GET_INDEX") {
                        // Send my local shared content headers
                        val sharedDb = AetherDatabase.getSharedDatabase()
                        val myShared = sharedDb.contentDao().getAll()
                        val headers = myShared.map { local ->
                            ContentHeader(
                                contentHash = local.contentHash,
                                title = local.title,
                                authorAlias = local.authorAlias,
                                timestamp = local.timestamp,
                                thumbnailBase64 = local.thumbnailBase64,
                                categoryFlags = local.categoryFlags,
                                emotionFlags = local.emotionFlags,
                                reputationScore = local.likeTokens.size - local.dislikeTokens.size,
                                spaceId = local.spaceId
                            )
                        }
                        val json = Json.encodeToString(headers)
                        webRtcManager.sendData(json)
                    } else if (message.startsWith("[")) { // Simple JSON array check
                        // Received peer's content headers
                        val headers = Json.decodeFromString<List<ContentHeader>>(message)
                        app.clearspace.network.discovery.PeerRepository.addGlobalWebRtcHeaders("webrtc-global", headers)
                        android.util.Log.d("DiscoveryForegroundService", "Received ${headers.size} headers via WebRTC")
                        webRtcManager.close() // Close connection after sync
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DiscoveryForegroundService", "Error in WebRTC onDataReceived", e)
                }
            }
        }

        val swarmAgent = WebRtcSwarmAgent(this)
        coroutineScope.launch {
            while (isActive) {
                try {
                    swarmAgent.syncWithRandomGlobalPeer()
                } catch (e: Exception) {}
                kotlinx.coroutines.delay(10 * 60 * 1000L) // Random global peer every 10 mins
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager?.stop()
        p2pServer?.stop()
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) {}
        try { unbindService(connection) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "ClearSpace Sharing"
            val descriptionText = "Persistent notification when sharing is active"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        val stopIntent = Intent(this, DiscoveryForegroundService::class.java).apply {
            action = ACTION_STOP_SHARING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClearSpace Sharing is Active")
            .setContentText("Your device is discoverable and participating in the network.")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop Sharing", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

