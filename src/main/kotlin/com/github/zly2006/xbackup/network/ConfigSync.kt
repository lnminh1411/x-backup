package com.github.zly2006.xbackup.network

import com.github.zly2006.xbackup.Config
import com.github.zly2006.xbackup.XBackup
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

class ConfigSyncPayload(val configJson: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigSyncPayload>(Identifier.fromNamespaceAndPath("xbackup", "config_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, ConfigSyncPayload> = StreamCodec.of(
            { buf, value -> buf.writeUtf(value.configJson) },
            { buf -> ConfigSyncPayload(buf.readUtf()) }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class ConfigSavePayload(val configJson: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigSavePayload>(Identifier.fromNamespaceAndPath("xbackup", "config_save"))
        val CODEC: StreamCodec<FriendlyByteBuf, ConfigSavePayload> = StreamCodec.of(
            { buf, value -> buf.writeUtf(value.configJson) },
            { buf -> ConfigSavePayload(buf.readUtf()) }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
