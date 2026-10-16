package com.kisisel.podpil

import android.app.Application

class PodPilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PodRepository.init(this)
    }
}
