package dev.spelfrog.getsynced

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.spelfrog.getsynced.git.GitSetup

class App : Application() {
    companion object {
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_RESULT = "sync_result"
    }

    override fun onCreate() {
        super.onCreate()
        GitSetup.init(filesDir)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_sync_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_result_desc)
            }
        )
    }
}
