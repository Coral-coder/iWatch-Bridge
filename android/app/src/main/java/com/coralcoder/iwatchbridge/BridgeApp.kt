package com.coralcoder.iwatchbridge

import android.app.Application
import com.coralcoder.iwatchbridge.core.BridgeController

class BridgeApp : Application() {
    lateinit var controller: BridgeController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = BridgeController(this)
    }
}

val android.content.Context.bridge: BridgeController
    get() = (applicationContext as BridgeApp).controller
