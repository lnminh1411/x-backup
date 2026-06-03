package com.github.zly2006.xbackup.client

import com.github.zly2006.xbackup.Config
import com.github.zly2006.xbackup.XBackup
import com.github.zly2006.xbackup.network.ConfigSyncPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object XBackupClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.TYPE) { payload, context ->
            try {
                val newConfig = XBackup.json.decodeFromString<Config>(payload.configJson)
                XBackup.config = newConfig
                XBackup.log.info("Successfully synchronized config with server")
            } catch (e: Exception) {
                XBackup.log.error("Failed to decode synchronized config from server", e)
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            XBackup.loadConfig()
            XBackup.log.info("Disconnected from server, reloaded local config")
        }
    }
}
