package com.github.zly2006.xbackup

import com.github.zly2006.xbackup.Utils.broadcast
import com.github.zly2006.xbackup.Utils.save
import com.github.zly2006.xbackup.Utils.setAutoSaving
import com.github.zly2006.xbackup.api.XBackupApi
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.ktor.client.*
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import com.github.zly2006.xbackup.network.ConfigSyncPayload
import com.github.zly2006.xbackup.network.ConfigSavePayload
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.server.permissions.Permission
import net.minecraft.server.MinecraftServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteDataSource
import java.io.File
import java.net.http.HttpClient.Redirect.NORMAL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.*

object XBackup : ModInitializer {
    lateinit var config: Config
    private val configPath = FabricLoader.getInstance().configDir.resolve("x-backup.config.json")
    val log = LoggerFactory.getLogger("XBackup")!!
    const val MOD_VERSION = "2.0.0"
    const val GIT_COMMIT = "72cc36c"
    const val COMMIT_DATE = "2026-01-12T11:45:52+08:00"
    var _service: BackupDatabaseService? = null
    val service get() = _service!!
    var server: MinecraftServer? = null

    @get:JvmName("isServerStarted")
    val serverStarted get() = server != null
    @get:JvmName("isRestoring")
    var restoring = false
    var serverStopHook: (MinecraftServer) -> Unit = {}

    // Backup
    var isBusy = false

    // Restore
    var reason = ""
        set(value) {
            field = value
            log.info("Restore reason: $value")
        }
    var blockPlayerJoin = false
    var disableSaving = false
    var disableWatchdog = false
    private var hasLoggedSkip = false
    var playersLoggedOnSinceLastBackup = false
        set(value) {
            field = value
            if (value) {
                hasLoggedSkip = false
            }
        }
    var lastBackupAttemptTime = 0L

    enum class BackgroundState {
        IDLE, UNKNOWN, SCHEDULED_BACKUP, PRUNING, STOPPED
    }

    var backgroundState = BackgroundState.UNKNOWN
    var crontabJob: Job? = null

    fun loadConfig() {
        try {
            config = (if (configPath.exists()) json.decodeFromString(configPath.readText())
            else Config())
            config.language = I18n.setLanguage(config.language)
        } catch (e: Exception) {
            log.error("Error loading config", e)
            config = Config()
        }
        saveConfig()
    }

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        encodeDefaults = true
        prettyPrint = true
        allowTrailingComma = true
        ignoreUnknownKeys = true
    }

    fun saveConfig() {
        try {
            configPath.writeText(json.encodeToString(config))
            _service?.let { service ->
                val newBlobPath = if (config.mirrorMode) {
                    Path(config.mirrorFrom ?: "").resolve(config.blobPath).absolute().normalize()
                } else {
                    Path("").absolute().resolve(config.blobPath).normalize()
                }
                service.blobDir = newBlobPath
            }
        } catch (e: Exception) {
            log.error("Error saving config", e)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun onInitialize() {
        runCatching {
            loadConfig()
        }
        PayloadTypeRegistry.clientboundPlay().register(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ConfigSavePayload.TYPE, ConfigSavePayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ConfigSavePayload.TYPE) { payload, context ->
            val player = context.player()
            val server = context.server()
            val requiredLevel = config.operatorPermissionLevel
            val permission = when {
                requiredLevel <= 0 -> null
                requiredLevel <= 1 -> PermissionLevel.MODERATORS
                requiredLevel <= 2 -> PermissionLevel.GAMEMASTERS
                requiredLevel <= 3 -> PermissionLevel.ADMINS
                else -> PermissionLevel.OWNERS
            }
            val allowed = permission == null || player.permissions().hasPermission(Permission.HasCommandLevel(permission))
            if (allowed) {
                try {
                    val newConfig = json.decodeFromString<Config>(payload.configJson)
                    config = newConfig
                    saveConfig()
                    
                    val syncPayload = ConfigSyncPayload(payload.configJson)
                    server.playerList.players.forEach { p ->
                        if (ServerPlayNetworking.canSend(p, ConfigSyncPayload.TYPE)) {
                            ServerPlayNetworking.send(p, syncPayload)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to decode and save synced config", e)
                }
            } else {
                log.warn("Player ${player.scoreboardName} tried to modify config without sufficient permissions")
            }
        }

        if (config.mirrorMode) {
            if (config.mirrorFrom == null) {
                log.error("Mirror mode is enabled but mirrorFrom is not set")
                error("Mirror mode is enabled but mirrorFrom is not set")
            }
            val mirrorFrom = File(config.mirrorFrom!!)
            if (!mirrorFrom.isDirectory) {
                log.error("Mirror mode is enabled but mirrorFrom is not a directory")
                error("Mirror mode is enabled but mirrorFrom is not a directory")
            }
            if (!mirrorFrom.resolve("server.properties").exists() || !mirrorFrom.resolve("world").exists()) {
                log.error("Mirror mode is enabled but mirrorFrom is not a valid server directory")
                error("Mirror mode is enabled but mirrorFrom is not a valid server directory")
            }
        }
        if (System.getProperty("xb.restart") == "true") {
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) {
                ProcessBuilder(RestartUtils.generateWindowsRestartCommand())
                    .start()
            } else if (os.contains("mac") || os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                ProcessBuilder(RestartUtils.generateUnixRestartCommand())
                    .start()
            } else {
                error("Unsupported operating system")
            }

            log.info("Restarting...")
            Runtime.getRuntime().exit(0)
        }
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            Commands.register(dispatcher)
        })
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            playersLoggedOnSinceLastBackup = true
            val player = handler.player
            if (ServerPlayNetworking.canSend(player, ConfigSyncPayload.TYPE)) {
                try {
                    val configJson = json.encodeToString(config)
                    ServerPlayNetworking.send(player, ConfigSyncPayload(configJson))
                } catch (e: Exception) {
                    log.error("Failed to send config sync packet to player", e)
                }
            }
        }
        ServerLifecycleEvents.SERVER_STARTING.register {
            restoring = false
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server

            server.commands.performPrefixedCommand(XBackup.server!!.createCommandSourceStack(), "1")
            kotlin.runCatching {
                // sync client language to the integrated server
                config.language = I18n.setLanguage(Minecraft.getInstance().options.languageCode)
            }
            val worldPath = if (config.mirrorMode) {
                File(config.mirrorFrom!!).toPath().resolve("world")
            }
            else {
                server.getWorldPath(LevelResource.ROOT)
            }.toAbsolutePath().normalize()
            checkAndMigrateLegacyDatabase(worldPath)
            val database = getDatabaseFromWorld(worldPath)
            if (config.mirrorMode) {
                val sourceConfig = kotlin.runCatching {
                    json.decodeFromStream<Config>(
                        Path(
                            config.mirrorFrom!!,
                            "config",
                            "x-backup.config.json"
                        ).inputStream()
                    )
                }.getOrNull()
                if (sourceConfig == null) {
                    log.error("Failed to load config from source server!")
                }
                val config = sourceConfig ?: config
                _service = BackupDatabaseService(
                    worldPath,
                    database,
                    Path(this.config.mirrorFrom!!).resolve(config.blobPath).absolute().normalize(),
                    config
                )
            }
            else {
                _service = BackupDatabaseService(
                    worldPath,
                    database,
                    Path("").absolute().resolve(config.blobPath).normalize(),
                    config
                )
            }
            XBackupApi.setInstance(service)
            if (!config.mirrorMode) {
                startCrontabJob(server)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            if (!restoring) {
                XBackupApi.setInstance(null)
                _service?.close()
                _service = null
                server = null
            }
            runBlocking {
                crontabJob?.cancelAndJoin()
            }
        }
    }

    fun getDatabaseFromWorld(worldPath: Path?): Database {
        val database = Database.connect(
            SQLiteDataSource(
                SQLiteConfig().apply {
                    enforceForeignKeys(true)
                    setCacheSize(100_000)
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                }
            ).apply {
                url = "jdbc:sqlite:$worldPath/x_backup.db"
            }
        )
        TransactionManager.defaultDatabase = database
        return database
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startCrontabJob(server: MinecraftServer) {
        require(!config.mirrorMode) {
            "Crontab job should not be started in mirror mode"
        }
        crontabJob = GlobalScope.launch {
            while (true) {
                backgroundState = BackgroundState.IDLE
                delay(10000)
                val backup = service.getLatestBackup()
                if (isBusy) continue
                if (config.pruneConfig.enabled) {
                    backgroundState = BackgroundState.PRUNING
                    prune(server)
                }
                if (config.backupInterval > 0) {
                    backgroundState = BackgroundState.SCHEDULED_BACKUP
                    val now = System.currentTimeMillis()
                    if (now - lastBackupAttemptTime < 300_000) {
                        continue
                    }
                    val isDue = if (config.schedulerMode == Config.SchedulerMode.GAME_TIME) {
                        val lastGameTime = backup?.metadata?.get("game_time")?.jsonPrimitive?.longOrNull
                        if (lastGameTime != null) {
                            val elapsedTicks = server.overworld().gameTime - lastGameTime
                            elapsedTicks / 20 > config.backupInterval
                        } else {
                            backup == null || (now - backup.created) / 1000 > config.backupInterval
                        }
                    } else {
                        backup == null || (now - backup.created) / 1000 > config.backupInterval
                    }
                    if (isDue) {
                        if (config.pauseAutomaticBackupsWithoutPlayers) {
                            val playersOnline = server.playerList.playerCount > 0
                            if (playersOnline) {
                                playersLoggedOnSinceLastBackup = true
                            }
                            if (!playersLoggedOnSinceLastBackup) {
                                if (!hasLoggedSkip) {
                                    log.info("Skipping scheduled backup because no players logged on since the last backup.")
                                    hasLoggedSkip = true
                                }
                                delay(10000)
                                continue
                            }
                        }
                        try {
                            isBusy = true
                            lastBackupAttemptTime = System.currentTimeMillis()
                            withContext(server.asCoroutineDispatcher()) {
                                val startMsg = Utils.formatMessage(
                                    config.messageConfig.scheduledBackupStart,
                                    server = server,
                                    taskName = "Scheduled Backup"
                                )
                                server.broadcast(Component.literal(startMsg))
                                server.save()
                                server.setAutoSaving(false)
                                disableSaving = true
                            }
                            val progressJob = startProgressTracker("Scheduled Backup")
                            val result = try {
                                service.createBackup(
                                    server.getWorldPath(LevelResource.ROOT).toAbsolutePath(),
                                    I18n["message.xb.scheduled_backup"],
                                    metadata = buildJsonObject {
                                        put("scheduled", true)
                                        put("interval", config.backupInterval)
                                        put("mod_ver", MOD_VERSION)
                                        put("game_time", server.overworld().gameTime)
                                    }
                                )
                            } finally {
                                progressJob.cancel()
                            }
                            if (result.success) {
                                val localBackup = File("x_backup.db.back")
                                localBackup.delete()
                                try {
                                    (service.database.connector().connection as? SQLiteConnection)?.createStatement()
                                        ?.execute("VACUUM INTO '$localBackup';")
                                } catch (e: Exception) {
                                    log.error("Error backing up database", e)
                                }
                                Files.move(
                                    localBackup.toPath(),
                                    Path("xb.backups")
                                        .resolve(result.backId.toString())
                                        .resolve("x_backup.db")
                                        .createParentDirectories(),
                                    StandardCopyOption.REPLACE_EXISTING
                                )
                                // delete old backups in ./xb.backups, keep the latest 5
                                val backups = Path("xb.backups").listDirectoryEntries().filter { it.isDirectory() }
                                backups.sortedByDescending { it.getLastModifiedTime().toMillis() }
                                    .drop(5)
                                    .forEach { it.toFile().deleteRecursively() }
                                val finishedMsg = Utils.formatMessage(
                                    config.messageConfig.scheduledBackupFinished,
                                    server = server,
                                    player = "System",
                                    taskName = "Scheduled Backup",
                                    backupId = result.backId,
                                    totalSize = result.totalSize,
                                    compressedSize = result.compressedSize,
                                    addedSize = result.addedSize,
                                    timeTakenMillis = result.millis,
                                    filesTotal = result.totalFilesCount,
                                    filesChanged = result.filesChangedCount,
                                    filesReused = result.filesReusedCount
                                )
                                server.broadcast(Component.literal(finishedMsg))
                                playersLoggedOnSinceLastBackup = server.playerList.playerCount > 0
                                if (config.remoteConfig.enabled && config.remoteConfig.syncOnBackup) {
                                    RemoteSyncService.syncToRemote(
                                        result.backId,
                                        I18n["message.xb.scheduled_backup"],
                                        server.getWorldPath(LevelResource.ROOT).toAbsolutePath(),
                                        service,
                                        config,
                                        server
                                    )
                                }
                            } else {
                                if (result.message == "EMPTY_BACKUP") {
                                    log.info("Scheduled backup cancelled: No changes detected.")
                                    playersLoggedOnSinceLastBackup = server.playerList.playerCount > 0
                                } else {
                                    log.error("Scheduled backup failed: ${result.message}")
                                }
                            }
                        } catch (e: Throwable) {
                            if (e is CancellationException) {
                                throw e
                            }
                            log.error("Crontab backup failed", e)
                        } finally {
                            isBusy = false
                            withContext(server.asCoroutineDispatcher()) {
                                disableSaving = false
                                server.setAutoSaving(true)
                            }
                        }
                    }
                }
            }
        }.apply {
            invokeOnCompletion {
                backgroundState = BackgroundState.STOPPED
            }
        }
    }

    suspend fun prune(server: MinecraftServer): Int {
        val allBackups = service.listBackups(0, Int.MAX_VALUE)
        val latest = service.getLatestBackup()

        val idToTime = allBackups.filter {
            !it.temporary && it.metadata?.get("scheduled")?.jsonPrimitive?.booleanOrNull == true
        }.associate { it.id.toString() to it.created }
        val toPrune = config.pruneConfig.prune(idToTime, System.currentTimeMillis())
        var count = 0
        if (toPrune.isNotEmpty()) {
            try {
                isBusy = true
                server.broadcast(Utils.translate("message.xb.running_prune"))
                toPrune.forEach {
                    if (it == latest?.id?.toString()) {
                        // skip the latest backup
                        // Some players configured keep policy wrongly, and prune keeps deleting the latest backup
                        // and then do scheduled backup again and again
                        return@forEach
                    }
                    server.broadcast(Utils.translate("message.xb.pruning_backup", it))
                    service.deleteBackupInternal(service.getBackup(it.toInt())!!)
                    count++
                }
                server.broadcast(Utils.translate("message.xb.prune_finished", toPrune.size))
            } catch (e: Exception) {
                log.error("Crontab prune failed", e)
            } finally {
                isBusy = false
            }
        }

        allBackups.filter {
            it.temporary && it.created < System.currentTimeMillis() - config.pruneConfig.temporaryKeepPolicy()
        }.forEach {
            service.deleteBackupInternal(it)
            count++
        }
        return count
    }

    private fun checkAndMigrateSingleDatabase(dbFile: File) {
        val parentDir = dbFile.parentFile
        var isLegacy = false
        try {
            val dataSource = SQLiteDataSource().apply {
                url = "jdbc:sqlite:${dbFile.absolutePath}"
            }
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    var hasTable = false
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='backup_entries'").use { rs ->
                        if (rs.next()) {
                            hasTable = true
                        }
                    }
                    if (hasTable) {
                        stmt.executeQuery("SELECT COUNT(*) FROM backup_entries WHERE compress = 1 OR compress = 2").use { rs ->
                            if (rs.next() && rs.getInt(1) > 0) {
                                isLegacy = true
                            }
                        }
                        if (!isLegacy) {
                            stmt.executeQuery("SELECT hash FROM backup_entries LIMIT 10").use { rs ->
                                while (rs.next()) {
                                    val hash = rs.getString("hash")
                                    if (hash != null && hash.length == 32) {
                                        isLegacy = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("[X Backup] Error checking legacy database: ${dbFile.absolutePath}", e)
        }

        if (isLegacy) {
            log.warn("[X Backup] Legacy backups using MD5 or unsupported compression detected in database: ${dbFile.absolutePath}. Renaming database to preserve compatibility for downgrade.")
            val legacyFile = parentDir.resolve("x_backup.db.legacy")
            if (legacyFile.exists()) {
                legacyFile.delete()
            }
            if (dbFile.renameTo(legacyFile)) {
                log.info("[X Backup] Successfully renamed legacy database to ${legacyFile.name}")
                // Also rename WAL/SHM files to prevent database corruption/conflicts when a new one is created
                val walFile = parentDir.resolve("x_backup.db-wal")
                if (walFile.exists()) {
                    val legacyWalFile = parentDir.resolve("x_backup.db-wal.legacy")
                    if (legacyWalFile.exists()) legacyWalFile.delete()
                    walFile.renameTo(legacyWalFile)
                }
                val shmFile = parentDir.resolve("x_backup.db-shm")
                if (shmFile.exists()) {
                    val legacyShmFile = parentDir.resolve("x_backup.db-shm.legacy")
                    if (legacyShmFile.exists()) legacyShmFile.delete()
                    shmFile.renameTo(legacyShmFile)
                }
            } else {
                log.error("[X Backup] Failed to rename legacy database: ${dbFile.absolutePath}")
            }
        }
    }

    fun checkAndMigrateLegacyDatabase(worldPath: Path) {
        val dbFile = worldPath.resolve("x_backup.db").toFile()
        if (dbFile.exists()) {
            checkAndMigrateSingleDatabase(dbFile)
        }

        val xbBackupsDir = Path("xb.backups").toFile()
        if (xbBackupsDir.exists() && xbBackupsDir.isDirectory) {
            xbBackupsDir.listFiles()?.forEach { backupDir ->
                if (backupDir.isDirectory) {
                    val backupDbFile = backupDir.resolve("x_backup.db")
                    if (backupDbFile.exists()) {
                        checkAndMigrateSingleDatabase(backupDbFile)
                    }
                }
            }
        }
    }

    fun ensureNotBusy(
        context: CoroutineContext = server!!.asCoroutineDispatcher(),
        source: CommandSourceStack? = null,
        block: suspend () -> Unit
    ) {
        require(server!!.isSameThread)
        if (isBusy) {
            throw SimpleCommandExceptionType(Component.literal("Backup is already running")).create()
        }
        isBusy = true
        service.launch(context) {
            try {
                block()
            }
            catch (e: Throwable) {
                log.error("Error running X Backup task", e)
                source?.sendFailure(Component.literal("Error running X Backup task: ${e.message}"))
            }
            finally {
                isBusy = false
            }
        }
    }

    fun startProgressTracker(taskName: String): Job {
        return service.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            delay(10000)
            while (isActive && isBusy) {
                val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
                val totalBytes = service.totalBytesToProcess.get()
                val processed = service.processedBytes.get()
                val totalFiles = service.totalFilesToProcess.get()
                val doneFiles = service.processedFiles.get()

                val percent = if (totalBytes > 0) (processed * 100 / totalBytes) else 0
                val processedStr = Utils.sizeToString(processed)
                val totalStr = Utils.sizeToString(totalBytes)

                val msg = if (service.backupPhase == BackupDatabaseService.BackupPhase.BACKUP) {
                    Utils.formatMessage(
                        config.messageConfig.backupProgress,
                        server = server,
                        taskName = taskName,
                        progressPercent = percent.toInt(),
                        progressBytesStr = "$processedStr / $totalStr",
                        progressFilesStr = "$doneFiles / $totalFiles",
                        timeElapsedSeconds = totalElapsed,
                        totalSize = totalBytes
                    )
                } else {
                    val percentFiles = if (totalFiles > 0) (doneFiles * 100 / totalFiles) else 0
                    Utils.formatMessage(
                        config.messageConfig.otherProgress,
                        server = server,
                        taskName = taskName,
                        progressPercent = percentFiles,
                        progressBytesStr = "$processedStr / $totalStr",
                        timeElapsedSeconds = totalElapsed
                    )
                }

                server?.broadcast(Component.literal(msg))

                delay(config.progressLogInterval.coerceAtLeast(1) * 1000L)
            }
        }
    }
}
