package com.github.zly2006.xbackup

import net.minecraft.server.MinecraftServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.dimension.DimensionType
import java.nio.file.Path
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.server.permissions.Permission

@Suppress("NOTHING_TO_INLINE")
object Utils {
    inline fun translate(key: String, vararg args: Any): MutableComponent {
        return Component.translatableWithFallback(
            key,
            I18n[key],
            *args
        )
    }

    inline fun CommandSourceStack.send(text: Component) {
        sendSystemMessage(text)
    }

    inline fun MinecraftServer.setAutoSaving(value: Boolean) {
        allLevels.forEach { it.noSave = !value }
    }

    inline fun MinecraftServer.save() {
        saveEverything(false, false, true)
        forceSynchronousWrites()
    }

    inline fun MinecraftServer.finishRestore() {
        XBackup.blockPlayerJoin = false
        XBackup.disableWatchdog = false
        XBackup.disableSaving = false
        XBackup.restoring = false

        running = true
        stopped = false
        runServer()
    }

    inline fun MinecraftServer.broadcast(text: Component) {
        val config = XBackup.config
        if (config.broadcastBackupInChat) {
            if (config.onlyBroadcastToOp) {
                playerList.players.forEach { player ->
                    val source = player.createCommandSourceStack()
                    val isOp = try {
                        me.lucko.fabric.api.permissions.v0.Permissions.check(source, "x_backup.broadcast", config.operatorPermissionLevel)
                    } catch (_: NoClassDefFoundError) {
                        val permission = when {
                            config.operatorPermissionLevel <= 0 -> null
                            config.operatorPermissionLevel <= 1 -> PermissionLevel.MODERATORS
                            config.operatorPermissionLevel <= 2 -> PermissionLevel.GAMEMASTERS
                            config.operatorPermissionLevel <= 3 -> PermissionLevel.ADMINS
                            else -> PermissionLevel.OWNERS
                        }
                        permission == null || source.permissions().hasPermission(Permission.HasCommandLevel(permission))
                    }
                    if (isOp) {
                        player.sendSystemMessage(text)
                    }
                }
            } else {
                playerList.broadcastSystemMessage(text, false)
            }
        }
        XBackup.log.info(text.string)
    }

    fun isFileInWorld(world: ServerLevel, p: Path): Boolean {
        val path = DimensionType.getStorageFolder(
            world.dimension(),
            world.server.getWorldPath(LevelResource.ROOT).toAbsolutePath()
        ).normalize()
        return p.normalize().startsWith(path)
    }
}
