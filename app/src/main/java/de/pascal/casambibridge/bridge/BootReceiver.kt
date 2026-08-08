package de.pascal.casambibridge.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val config = ConfigStore.load(context)
            if (config.autoStartEnabled) {
                LogBus.log("Autostart: Bridge wird nach $action gestartet")
                context.startService(Intent(context, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
            }
        }
    }
}
