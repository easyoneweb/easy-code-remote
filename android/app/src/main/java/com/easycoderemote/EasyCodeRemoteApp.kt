package com.easycoderemote

import android.app.Application
import com.easycoderemote.data.repo.Repository

class EasyCodeRemoteApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
    }
}