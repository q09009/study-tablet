package com.q09009.studytablet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class StudyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(RECORDING_CHANNEL, "녹음", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val RECORDING_CHANNEL = "recording"
    }
}
