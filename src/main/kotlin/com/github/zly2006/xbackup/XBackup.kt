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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundTabListPacket
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
    const val MOD_VERSION = "1.2.1"
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
            if (os.contains("mac") || os.contains("nix") || os.contains("nux") || os.contains("aix")) {
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
        ServerPlayConnectionEvents.JOIN.register { _, _, _ ->
            playersLoggedOnSinceLastBackup = true
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
                    if (backup == null || (now - backup.created) / 1000 > config.backupInterval) {
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
                                server.broadcast(Utils.translate("message.xb.running_scheduled_backup"))
                                server.save()
                                server.setAutoSaving(false)
                                disableSaving = true
                            }
                            val result = service.createBackup(
                                server.getWorldPath(LevelResource.ROOT).toAbsolutePath(),
                                I18n["message.xb.scheduled_backup"],
                                metadata = buildJsonObject {
                                    put("scheduled", true)
                                    put("interval", config.backupInterval)
                                    put("mod_ver", MOD_VERSION)
                                }
                            )
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
                                server.broadcast(
                                    Utils.translate(
                                        "message.xb.scheduled_backup_finished",
                                        backupIdText(result.backId),
                                        sizeText(result.totalSize),
                                        sizeText(result.compressedSize),
                                        sizeText(result.addedSize),
                                        result.millis
                                    )
                                )
                                playersLoggedOnSinceLastBackup = false
                            } else {
                                if (result.message == "EMPTY_BACKUP") {
                                    log.info("Scheduled backup cancelled: No changes detected.")
                                    playersLoggedOnSinceLastBackup = false
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
        val backups = service.listBackups(0, Int.MAX_VALUE).filter {
            it.created < System.currentTimeMillis() - config.pruneConfig.temporaryKeepPolicy()
        }
        val latest = service.getLatestBackup()

        val idToTime = backups.filter {
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

        backups.filter { it.temporary }.forEach {
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
}
