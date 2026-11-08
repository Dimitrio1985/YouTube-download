package com.example.ytdl

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
    }
}
