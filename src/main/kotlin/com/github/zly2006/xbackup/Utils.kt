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

    fun sizeToString(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format(java.util.Locale.US, "%.2f%sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    fun formatMessage(
        template: String,
        server: MinecraftServer?,
        player: String = "System",
        taskName: String = "",
        backupId: Int? = null,
        totalSize: Long? = null,
        compressedSize: Long? = null,
        addedSize: Long? = null,
        timeTakenMillis: Long? = null,
        filesTotal: Int? = null,
        filesChanged: Int? = null,
        filesReused: Int? = null,
        progressPercent: Int? = null,
        progressBytesStr: String? = null,
        progressFilesStr: String? = null,
        timeElapsedSeconds: Long? = null
    ): String {
        val now = java.time.ZonedDateTime.now()
        
        var ramStr = "N/A"
        var totalDiskStr = "N/A"
        var systemDiskUsedStr = "N/A"
        
        if (server != null) {
            try {
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                ramStr = sizeToString(usedMemory)
            } catch (_: Exception) {}
            
            try {
                val store = java.nio.file.Files.getFileStore(XBackup.service.blobDir)
                totalDiskStr = sizeToString(store.totalSpace)
                systemDiskUsedStr = sizeToString(store.totalSpace - store.usableSpace)
            } catch (_: Exception) {}
        }
        
        var totalBackupSizeStr = "N/A"
        if (server != null) {
            try {
                val totalBackupBytes = XBackup.service.blobDir.toFile().walk().filter { it.isFile }.sumOf { it.length() }
                totalBackupSizeStr = sizeToString(totalBackupBytes)
            } catch (_: Exception) {}
        }
        
        val tkStr = timeTakenMillis?.let { String.format(java.util.Locale.US, "%.2f", it / 1000.0) } ?: "N/A"
        val tkMsStr = timeTakenMillis?.toString() ?: "N/A"
        
        return template
            .replace("%PL%", player)
            .replace("%PL_BY%", if (player == "System" || player == "Server" || player.isEmpty()) "" else " by $player")
            .replace("%ID%", backupId?.toString() ?: "N/A")
            .replace("%TN%", taskName)
            .replace("%Y%", now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy")))
            .replace("%y%", now.format(java.time.format.DateTimeFormatter.ofPattern("yy")))
            .replace("%m%", now.format(java.time.format.DateTimeFormatter.ofPattern("MM")))
            .replace("%-m%", now.format(java.time.format.DateTimeFormatter.ofPattern("M")))
            .replace("%d%", now.format(java.time.format.DateTimeFormatter.ofPattern("dd")))
            .replace("%e%", now.dayOfMonth.toString().padStart(2, ' '))
            .replace("%H%", now.format(java.time.format.DateTimeFormatter.ofPattern("HH")))
            .replace("%I%", now.format(java.time.format.DateTimeFormatter.ofPattern("hh")))
            .replace("%M%", now.format(java.time.format.DateTimeFormatter.ofPattern("mm")))
            .replace("%S%", now.format(java.time.format.DateTimeFormatter.ofPattern("ss")))
            .replace("%p%", now.format(java.time.format.DateTimeFormatter.ofPattern("a")))
            .replace("%z%", now.format(java.time.format.DateTimeFormatter.ofPattern("Z")))
            .replace("%Z%", now.format(java.time.format.DateTimeFormatter.ofPattern("z")))
            .replace("%a%", now.format(java.time.format.DateTimeFormatter.ofPattern("E")))
            .replace("%A%", now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE")))
            .replace("%b%", now.format(java.time.format.DateTimeFormatter.ofPattern("MMM")))
            .replace("%B%", now.format(java.time.format.DateTimeFormatter.ofPattern("MMMM")))
            .replace("%R%", ramStr)
            .replace("%D%", totalDiskStr)
            .replace("%DW%", totalSize?.let { sizeToString(it) } ?: "N/A")
            .replace("%DM%", systemDiskUsedStr)
            .replace("%DB%", compressedSize?.let { sizeToString(it) } ?: "N/A")
            .replace("%DP%", totalSize?.let { sizeToString(it) } ?: "N/A")
            .replace("%DS%", totalBackupSizeStr)
            .replace("%FT%", filesTotal?.toString() ?: "N/A")
            .replace("%FC%", filesChanged?.toString() ?: "N/A")
            .replace("%FR%", filesReused?.toString() ?: "N/A")
            .replace("%FC_SZ%", addedSize?.let { sizeToString(it) } ?: "N/A")
            .replace("%BG%", progressPercent?.toString() ?: "N/A")
            .replace("%TL%", timeElapsedSeconds?.toString() ?: "N/A")
            .replace("%TK%", tkStr)
            .replace("%tk%", tkMsStr)
            .replace("%FB%", progressFilesStr ?: "N/A")
            .replace("%BD%", progressBytesStr ?: "N/A")
    }
}
