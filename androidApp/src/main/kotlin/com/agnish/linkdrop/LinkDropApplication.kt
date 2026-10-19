package com.agnish.linkdrop

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the one [PhoneConnection], so it outlives activities (rotation, reopening the app). */
class LinkDropApplication : Application() {
    lateinit var connection: PhoneConnection
        private set

    override fun onCreate() {
        super.onCreate()
        connection = PhoneConnection(
            local = AndroidDevice.load(this),
            sink = MediaStoreSink(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // ALLOW_PLAINTEXT is false in every release build (see androidApp/build.gradle.kts).
            allowPlaintext = BuildConfig.ALLOW_PLAINTEXT,
            // The Keystore identity is created on first use (in the background, when connecting).
            tls = PhoneTls(identity = { PhoneIdentity.loadOrCreate() }, exporter = AndroidKeyingMaterialExporter),
            paired = PairedDesktops.fromPreferences(this),
        )
    }
}
