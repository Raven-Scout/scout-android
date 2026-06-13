package com.jordanrburger.scout

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.jordanrburger.scout.data.ActionItemsRepository
import com.jordanrburger.scout.data.FileSnapshotStore
import com.jordanrburger.scout.data.NotificationRepository
import com.jordanrburger.scout.data.api.BridgeApi
import com.jordanrburger.scout.data.api.ScoutApi
import com.jordanrburger.scout.data.db.ScoutDatabase
import com.jordanrburger.scout.data.settings.EncryptedSettingsRepository
import com.jordanrburger.scout.data.settings.SettingsRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.jordanrburger.scout.worker.ForwardWorker
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val settings: SettingsRepository = EncryptedSettingsRepository(context)
    val db: ScoutDatabase = ScoutDatabase.get(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val api: BridgeApi = ScoutApi(client) { settings.bridgeConfig() }
    val actionItemsRepository = ActionItemsRepository(
        api,
        FileSnapshotStore(File(context.filesDir, "action-items-snapshot.json")),
        db.pendingActionDao(),
    )
    val notificationRepository = NotificationRepository(api, db.capturedNotificationDao())
}

class ScoutApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        ForwardWorker.schedulePeriodic(this)
        subscribeFcmIfConfigured()
    }

    /** Topic subscription only when Firebase is configured (spec §5 dormant-FCM rule). */
    private fun subscribeFcmIfConfigured() {
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "Scout alerts", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Urgent action items and connector alerts from Scout" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "Scout status", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Non-urgent updates and re-enable reminders" }
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "scout_alerts"
        const val CHANNEL_STATUS = "scout_status"
        const val FCM_TOPIC = "scout-default"

        fun container(context: Context): AppContainer =
            (context.applicationContext as ScoutApp).container
    }
}
