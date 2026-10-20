package com.agnish.linkdrop

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the one [PhoneConnection], so it outlives activities (rotation, reopening the app). */
class LinkDropApplication : Application() {
    lateinit var connection: PhoneConnection
        private set

    override fun onCreate() {
        super.onCreate()
        val sink = MediaStoreSink(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(Dispatchers.IO) { runCatching { sink.cleanUpPartials() } }
        connection = PhoneConnection(
            local = AndroidDevice.load(this),
            sink = sink,
            scope = scope,
            // ALLOW_PLAINTEXT is false in every release build (see androidApp/build.gradle.kts).
            allowPlaintext = BuildConfig.ALLOW_PLAINTEXT,
            // The Keystore identity is created on first use (in the background, when connecting).
            tls = PhoneTls(identity = { PhoneIdentity.loadOrCreate() }, exporter = AndroidKeyingMaterialExporter),
            paired = PairedDesktops.fromPreferences(this),
        )
    }
}
