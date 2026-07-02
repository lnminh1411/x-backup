package com.github.zly2006.xbackup

import com.github.zly2006.xbackup.api.*
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.*
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.*

@Suppress("SuspendFunctionOnCoroutineScope")
class BackupDatabaseService(
    val databaseDir: Path,
    val database: Database,
    var blobDir: Path,
    private val config: Config
) : CoroutineScope, XBackupKotlinAsyncApi {
    private val log = LoggerFactory.getLogger("XBackup")!!
    @OptIn(DelicateCoroutinesApi::class)
    private val syncExecutor = newFixedThreadPoolContext(1, "XBackup-Sync")

    override var activeTask: String = "Idle"

    /**
     * percentage of the active task
     */
    override var activeTaskProgress: Int = -1

    val totalBytesToProcess = java.util.concurrent.atomic.AtomicLong(0)
    val processedBytes = java.util.concurrent.atomic.AtomicLong(0)
    val totalFilesToProcess = java.util.concurrent.atomic.AtomicInteger(0)
    val processedFiles = java.util.concurrent.atomic.AtomicInteger(0)

    enum class BackupPhase {
        NONE, BACKUP
    }

    private data class FileToCommit(
        val sourceFile: File,
        val path: Path,
        val hash: String,
        val tempBlob: Path,
        val size: Long,
        val lastModified: Long,
        val zippedSize: Long,
        val compress: Byte
    )

    @Volatile
    var backupPhase = BackupPhase.NONE

    init {
        require(blobDir.isAbsolute && blobDir == blobDir.normalize()) {
            "Blob directory must be absolute and normalized"
        }
        if (!blobDir.isDirectory()) {
            log.warn("Blob directory not found, creating...")
        }

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                BackupEntryTable,
                BackupTable,
                BackupEntryBackupTable,
                withLogs = false
            )
        }
    }

    fun wrapOutputStream(outputStream: java.io.OutputStream): java.io.OutputStream {
        return when (config.compressionAlgorithm) {
            Config.CompressionAlgorithm.ZSTD -> {
                val zstdLevel = when (config.compressionLevel) {
                    1 -> 1
                    2 -> 3
                    3 -> 7
                    4 -> 12
                    5 -> 19
                    else -> 3
                }
                com.github.luben.zstd.ZstdOutputStream(outputStream, zstdLevel)
            }
            Config.CompressionAlgorithm.LZ4 -> {
                val lz4Factory = net.jpountz.lz4.LZ4Factory.fastestInstance()
                val compressor = when (config.compressionLevel) {
                    1 -> lz4Factory.fastCompressor()
                    2 -> lz4Factory.highCompressor(3)
                    3 -> lz4Factory.highCompressor(6)
                    4 -> lz4Factory.highCompressor(9)
                    5 -> lz4Factory.highCompressor(17)
                    else -> lz4Factory.fastCompressor()
                }
                net.jpountz.lz4.LZ4BlockOutputStream(outputStream, 1 shl 16, compressor)
            }
        }
    }
    
    private val ignoredFiles = setOf(
        "", // empty string is the root directory
        "x_backup.db.back",
        "x_backup.db",
        "x_backup.db-wal",
        "x_backup.db-shm",
        "x_backup.db-journal",
        "x_backup.db.legacy",
        "x_backup.db-wal.legacy",
        "x_backup.db-shm.legacy",
        "x_backup.db-journal.legacy",
        "x_backup.db.back.legacy",
        "chunk_tickets.dat"
    ) + config.ignoredFiles

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    object BackupEntryTable : IntIdTable("backup_entries") {
        val path = varchar("path", 255).index()
        val size = long("size")
        val zippedSize = long("zipped_size")
        val lastModified = long("last_modified")
        val isDirectory = bool("is_directory")
        val hash = varchar("hash", 255).index()

        /**
         * 0: no compress
         * 1: gzip
         * 2: zip (pack multiple files)
         */
        val compress = byte("compress").default(0)
        val cloudDriveId = byte("cloud_drive_id").default(0)
    }

    object BackupTable : IntIdTable("backups") {
        val size = long("size")
        val zippedSize = long("zipped_size")
        val created = long("created")
        val comment = varchar("comment", 255)
        val temporary = bool("temporary").default(false)
        val cloudBackupUrl = varchar("cloud_backup_url", 255).nullable()
        val metadata = json<JsonObject>("metadata", Json).nullable()
    }

    object BackupEntryBackupTable : IntIdTable("backup_entry_backup") {
        val backup = reference("backup", BackupTable, ReferenceOption.CASCADE).index()
        val entry = reference("entry", BackupEntryTable, ReferenceOption.CASCADE).index()
    }

    @Serializable
    data class BackupEntry(
        override val id: Int,
        override val path: String,
        override val size: Long,
        override val zippedSize: Long,
        override val lastModified: Long,
        override val isDirectory: Boolean,
        override val hash: String,
        override val compress: Int,
    ) : IBackupEntry {
        override fun toString(): String {
            return "$id:/$path"
        }

        override fun valid(service: XBackupApi): Boolean {
            return isDirectory ||
                    (service.getBlobFile(hash).exists() && (service.getBlobFile(hash).fileSize() == zippedSize))
        }

        override fun getInputStream(service: XBackupApi): InputStream? {
            return runBlocking { getInputStreamInternal(service as BackupDatabaseService) }
        }

        suspend fun getInputStreamInternal(service: BackupDatabaseService): InputStream? {
            val blob = service.getBlobFile(hash)
            if (!blob.exists()) {
                return null
            }
            try {
                return when (compress) {
                    0 -> blob.inputStream()
                    1 -> error("Legacy GZIP compression is not supported by this version of X Backup.")
                    2 -> error("Legacy ZIP compression is not supported by this version of X Backup.")
                    3 -> withContext(Dispatchers.IO) {
                        com.github.luben.zstd.ZstdInputStream(blob.inputStream())
                    }
                    4 -> withContext(Dispatchers.IO) {
                        net.jpountz.lz4.LZ4BlockInputStream(blob.inputStream())
                    }
                    else -> error("Unknown compress type: $compress")
                }
            } catch (e: ZipException) {
                log.error("Error reading zip file $hash", e)
                return null
            }
        }
    }

    @Serializable
    class Backup(
        override val id: Int,
        override val size: Long,
        override val zippedSize: Long,
        override val created: Long,
        override val comment: String,
        override val entries: List<BackupEntry>,
        override val temporary: Boolean,
        override val cloudBackupUrl: String?,
        val metadata: JsonObject?,
    ) : IBackup

    data class XBackupStatus(
        val blobDiskUsage: Long,
        val actualUsage: Long,
        val backupCount: Long,
        val latestBackup: Backup?,
    )

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val backId: Int,
        val totalSize: Long,
        val compressedSize: Long,
        val addedSize: Long,
        val millis: Long,
        val totalFilesCount: Int = 0,
        val filesChangedCount: Int = 0,
        val filesReusedCount: Int = 0
    )

    suspend fun status(): XBackupStatus {
        val blobDiskUsage = blobDir.toFile().walk().filter { it.isFile }.sumOf { it.length() }
        val actualUsage = dbQuery {
            BackupEntryTable.select(BackupEntryTable.zippedSize.sum())
                .firstOrNull()?.get(BackupEntryTable.zippedSize.sum()) ?: 0L
        }
        val backupCount = dbQuery { BackupTable.selectAll().count() }
        val latestBackup = getLatestBackup()
        return XBackupStatus(blobDiskUsage, actualUsage, backupCount, latestBackup)
    }

    fun verifyDirectoryWritable(path: Path): Boolean {
        if (!path.exists()) {
            try {
                path.createDirectories()
            } catch (e: Exception) {
                return false
            }
        }
        if (!path.isDirectory()) return false
        return try {
            val testFile = path.resolve(".xb_write_test_" + UUID.randomUUID().toString())
            testFile.writeText("test")
            val content = testFile.readText()
            testFile.deleteIfExists()
            content == "test"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun createBackup(
        root: Path,
        comment: String,
        temporary: Boolean = false,
        metadata: JsonObject? = null,
        predicate: (Path) -> Boolean = { true },
    ): BackupResult {
        if (!verifyDirectoryWritable(blobDir)) {
            error("Backup cancelled: Storage directory '$blobDir' is disconnected, not found, or not writable!")
        }
        if (blobDir.startsWith(root.absolute().normalize())) {
            error("Blob directory cannot be inside the backup directory")
        }
        
        // Pre-create all 256 subdirectories (00 to ff) and the .tmp folder under blobDir to avoid parallel NTFS metadata lock contention
        blobDir.resolve(".tmp").createDirectories()
        for (i in 0..255) {
            val hex = "%02x".format(i)
            blobDir.resolve(hex).createDirectories()
        }
        val files = ConcurrentHashMap.newKeySet<String>()
        val timeStart = System.currentTimeMillis()

        val limit = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        val backupDispatcher = Dispatchers.IO.limitedParallelism(limit)
        val allFiles = root.normalize().toFile().walk().filter {
            !shouldIgnore(it) && predicate(it.toPath())
        }.toList()

        // Cache all database entries to do in-memory lookups
        val allDbEntries = dbQuery {
            BackupEntryTable.selectAll().map { it.toBackupEntry() }
        }
        val dbEntriesByPath = allDbEntries.groupBy { it.path }
        val dbEntriesByHash = allDbEntries.associateBy { it.hash }

        data class EntryToInsert(
            val path: String,
            val size: Long,
            val lastModified: Long,
            val isDirectory: Boolean,
            val hash: String,
            val zippedSize: Long,
            val compress: Byte
        )
        val entriesToInsert = java.util.concurrent.ConcurrentLinkedQueue<EntryToInsert>()

        // Phase 1: Hashing & Direct Compression
        backupPhase = BackupPhase.BACKUP
        totalBytesToProcess.set(allFiles.sumOf { if (it.isFile) it.length() else 0L })
        processedBytes.set(0L)
        totalFilesToProcess.set(allFiles.size)
        processedFiles.set(0)

        val hashedEntries = ConcurrentHashMap<String, BackupEntry>()
        val needsCommit = ConcurrentHashMap.newKeySet<FileToCommit>()

        allFiles.map { sourceFile ->
            @Suppress("SuspendFunctionOnCoroutineScope")
            this.async(backupDispatcher) {
                retry(5) {
                    val path = root.normalize().relativize(sourceFile.toPath()).normalize()
                    files.add(path.toString())
                    
                    // In-memory look up for existing backup entries
                    val existing = dbEntriesByPath[path.toString()]?.firstOrNull { entry ->
                        if (entry.isDirectory != sourceFile.isDirectory) return@firstOrNull false
                        if (sourceFile.isFile) {
                            if (sourceFile.lastModified() % 1000 == 0L) {
                                false
                            }
                            else {
                                entry.size == sourceFile.length() && entry.lastModified == sourceFile.lastModified()
                            }
                        } else {
                            true
                        }
                    }?.takeIf { it.valid(this@BackupDatabaseService) }

                    if (existing != null) {
                        hashedEntries[path.toString()] = existing
                        processedFiles.incrementAndGet()
                        processedBytes.addAndGet(sourceFile.length())
                        return@retry
                    }

                    if (sourceFile.isDirectory) {
                        entriesToInsert.add(
                            EntryToInsert(
                                path = path.toString(),
                                size = 0,
                                lastModified = sourceFile.lastModified(),
                                isDirectory = true,
                                hash = "",
                                zippedSize = 0,
                                compress = 0
                            )
                        )
                        processedFiles.incrementAndGet()
                        return@retry
                    }

                    val beforeSize = sourceFile.length()
                    val beforeModified = sourceFile.lastModified()

                    if (beforeSize <= MEMORY_THRESHOLD) {
                        val shouldCompress = beforeSize > 1024
                        val digest = MessageDigest.getInstance("SHA-256")
                        val memoryStream = ByteArrayOutputStream(beforeSize.toInt().coerceAtMost(MEMORY_THRESHOLD))
                        val buffer = ByteArray(65536)
                        var attemptBytes = 0L
                        try {
                            if (shouldCompress) {
                                this@BackupDatabaseService.wrapOutputStream(memoryStream).use { output ->
                                    sourceFile.inputStream().use { input ->
                                        var read: Int
                                        while (input.read(buffer).also { read = it } > 0) {
                                            digest.update(buffer, 0, read)
                                            output.write(buffer, 0, read)
                                            processedBytes.addAndGet(read.toLong())
                                            attemptBytes += read
                                        }
                                    }
                                }
                            } else {
                                sourceFile.inputStream().use { input ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } > 0) {
                                        digest.update(buffer, 0, read)
                                        memoryStream.write(buffer, 0, read)
                                        processedBytes.addAndGet(read.toLong())
                                        attemptBytes += read
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            processedBytes.addAndGet(-attemptBytes)
                            throw e
                        }
                        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                        val zippedSize = memoryStream.size().toLong()

                        val afterSize = sourceFile.length()
                        val afterModified = sourceFile.lastModified()
                        if (beforeSize != afterSize || beforeModified != afterModified) {
                            error("File changed while creating backup, file: $path")
                        }

                        val blob = getBlobFile(sha256)
                        if (!blob.exists()) {
                            try {
                                blob.writeBytes(memoryStream.toByteArray())
                            } catch (e: IOException) {
                                if (!blob.exists()) throw e
                            }
                        }

                        val existingBlobEntry = dbEntriesByHash[sha256]
                        val finalZippedSize = existingBlobEntry?.zippedSize ?: zippedSize
                        val compressVal = existingBlobEntry?.compress ?: if (shouldCompress) {
                            if (config.compressionAlgorithm == Config.CompressionAlgorithm.LZ4) 4 else 3
                        } else 0

                        entriesToInsert.add(
                            EntryToInsert(
                                path = path.toString(),
                                size = beforeSize,
                                lastModified = beforeModified,
                                isDirectory = false,
                                hash = sha256,
                                zippedSize = finalZippedSize,
                                compress = compressVal.toByte()
                            )
                        )
                    } else {
                        val tempBlob = blobDir.resolve(".tmp").resolve(UUID.randomUUID().toString())
                        val shouldCompress = beforeSize > 1024
                        var sha256 = ""
                        var zippedSize: Long
                        var attemptBytes = 0L
                        try {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(65536)
                            if (shouldCompress) {
                                this@BackupDatabaseService.wrapOutputStream(tempBlob.outputStream()).use { output ->
                                    sourceFile.inputStream().use { input ->
                                        var read: Int
                                        while (input.read(buffer).also { read = it } > 0) {
                                            digest.update(buffer, 0, read)
                                            output.write(buffer, 0, read)
                                            processedBytes.addAndGet(read.toLong())
                                            attemptBytes += read
                                        }
                                    }
                                }
                            } else {
                                tempBlob.outputStream().use { output ->
                                    sourceFile.inputStream().use { input ->
                                        var read: Int
                                        while (input.read(buffer).also { read = it } > 0) {
                                            digest.update(buffer, 0, read)
                                            output.write(buffer, 0, read)
                                            processedBytes.addAndGet(read.toLong())
                                            attemptBytes += read
                                        }
                                    }
                                }
                            }
                            sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                            zippedSize = tempBlob.fileSize()

                            val afterSize = sourceFile.length()
                            val afterModified = sourceFile.lastModified()
                            if (beforeSize != afterSize || beforeModified != afterModified) {
                                tempBlob.deleteIfExists()
                                error("File changed while creating backup, file: $path")
                            }

                            val blob = getBlobFile(sha256)
                            if (blob.exists()) {
                                tempBlob.deleteIfExists()
                                val existingBlobEntry = dbEntriesByHash[sha256]
                                val finalZippedSize = existingBlobEntry?.zippedSize ?: blob.fileSize()
                                val compressVal = existingBlobEntry?.compress ?: if (shouldCompress) {
                                    if (config.compressionAlgorithm == Config.CompressionAlgorithm.LZ4) 4 else 3
                                } else 0

                                entriesToInsert.add(
                                    EntryToInsert(
                                        path = path.toString(),
                                        size = beforeSize,
                                        lastModified = beforeModified,
                                        isDirectory = false,
                                        hash = sha256,
                                        zippedSize = finalZippedSize,
                                        compress = compressVal.toByte()
                                    )
                                )
                            } else {
                                val compressVal = if (shouldCompress) {
                                    if (config.compressionAlgorithm == Config.CompressionAlgorithm.LZ4) 4 else 3
                                } else 0
                                needsCommit.add(
                                    FileToCommit(
                                        sourceFile = sourceFile,
                                        path = path,
                                        hash = sha256,
                                        tempBlob = tempBlob,
                                        size = beforeSize,
                                        lastModified = beforeModified,
                                        zippedSize = zippedSize,
                                        compress = compressVal.toByte()
                                    )
                                )
                            }
                        } catch (e: Throwable) {
                            processedBytes.addAndGet(-attemptBytes)
                            tempBlob.deleteIfExists()
                            throw e
                        }
                    }
                    processedFiles.incrementAndGet()
                }
            }
        }.awaitAll()

        // Phase 2: Finalize Blobs & DB entries (Instantaneous Rename/Move)
        needsCommit.map { fileToCommit ->
            @Suppress("SuspendFunctionOnCoroutineScope")
            this.async(backupDispatcher) {
                retry(5) {
                    val sourceFile = fileToCommit.sourceFile
                    val path = fileToCommit.path
                    val sha256 = fileToCommit.hash
                    val tempBlob = fileToCommit.tempBlob
                    val zippedSize = fileToCommit.zippedSize
                    val compressVal = fileToCommit.compress

                    val blob = getBlobFile(sha256)
                    if (blob.exists() && blob.fileSize() == zippedSize) {
                        tempBlob.deleteIfExists()
                    } else {
                        blob.createParentDirectories()
                        try {
                            tempBlob.moveTo(blob, StandardCopyOption.REPLACE_EXISTING)
                        } catch (e: IOException) {
                            if (blob.exists() && blob.fileSize() == zippedSize) {
                                tempBlob.deleteIfExists()
                            } else {
                                throw e
                            }
                        }
                    }

                    entriesToInsert.add(
                        EntryToInsert(
                            path = path.toString(),
                            size = sourceFile.length(),
                            lastModified = sourceFile.lastModified(),
                            isDirectory = false,
                            hash = sha256,
                            zippedSize = zippedSize,
                            compress = compressVal
                        )
                    )
                }
            }
        }.awaitAll()

        // Batch insert all new database entries in a single transaction
        val insertedEntries = if (entriesToInsert.isNotEmpty()) {
            dbQuery {
                BackupEntryTable.batchInsert(entriesToInsert) { item ->
                    this[BackupEntryTable.path] = item.path
                    this[BackupEntryTable.size] = item.size
                    this[BackupEntryTable.lastModified] = item.lastModified
                    this[BackupEntryTable.isDirectory] = item.isDirectory
                    this[BackupEntryTable.hash] = item.hash
                    this[BackupEntryTable.zippedSize] = item.zippedSize
                    this[BackupEntryTable.compress] = item.compress
                }.map { it.toBackupEntry() }
            }
        } else {
            emptyList()
        }

        insertedEntries.forEach { entry ->
            hashedEntries[entry.path] = entry
        }

        val newEntries = insertedEntries.filter { it.hash.isNotEmpty() && !dbEntriesByHash.containsKey(it.hash) }

        require(files.size == hashedEntries.size)
        val entries = hashedEntries.values.toList()
        Path("debug-backup.json").writeText(Json.encodeToString(files.toList()))
        log.info("[X Backup] Backed up ${entries.size} files, ${newEntries.size} new, ${entries.size - newEntries.size} files reused (Time taken: ${"%.2f".format((System.currentTimeMillis() - timeStart) / 1000.0)}s)")
        
        backupPhase = BackupPhase.NONE

        if (config.discardEmptyBackups && !temporary && newEntries.isEmpty()) {
            return BackupResult(
                success = false,
                message = "EMPTY_BACKUP",
                backId = -1,
                totalSize = entries.sumOf { it.size },
                compressedSize = entries.sumOf { it.zippedSize },
                addedSize = 0,
                millis = System.currentTimeMillis() - timeStart,
                totalFilesCount = entries.size,
                filesChangedCount = 0,
                filesReusedCount = entries.size
            )
        }
        val backup = dbQuery {
            val backupRow = BackupTable.insert {
                it[size] = entries.sumOf { it.size }
                it[zippedSize] = entries.sumOf { it.zippedSize }
                it[created] = System.currentTimeMillis()
                it[this.comment] = comment
                it[this.temporary] = temporary
                it[this.metadata] = metadata
            }.resultedValues!!.single()
            val backupId = backupRow[BackupTable.id].value
            
            // Batch insert relations
            BackupEntryBackupTable.batchInsert(entries) { entry ->
                this[BackupEntryBackupTable.backup] = backupId
                this[BackupEntryBackupTable.entry] = entry.id
            }
            // recheck
            val entryList = entries.filter {
                !it.isDirectory &&
                        (!getBlobFile(it.hash).exists() || getBlobFile(it.hash).fileSize() != it.zippedSize)
            }
            if (entryList.isNotEmpty()) {
                log.error(entryList.toString())
                error("Backup failed, ${entryList.size} files not backed up")
            }
            Backup(
                backupId,
                backupRow[BackupTable.size],
                backupRow[BackupTable.zippedSize],
                backupRow[BackupTable.created],
                backupRow[BackupTable.comment],
                entries,
                backupRow[BackupTable.temporary],
                backupRow[BackupTable.cloudBackupUrl],
                backupRow[BackupTable.metadata]
            )
        }
        return BackupResult(
            true,
            "OK",
            backup.id,
            backup.size,
            backup.zippedSize,
            newEntries.sumOf { it.zippedSize },
            System.currentTimeMillis() - timeStart,
            totalFilesCount = entries.size,
            filesChangedCount = newEntries.size,
            filesReusedCount = entries.size - newEntries.size
        )
    }

    override fun deleteBackup(backup: IBackup) = runBlocking {
        deleteBackupInternal(backup)
    }

    suspend fun deleteBackupInternal(backup: IBackup) {
        syncDbQuery {
            val entryIds = backup.entries.map { it.id }
            if (entryIds.isNotEmpty()) {
                val referencedEntryIds = BackupEntryBackupTable
                    .select(BackupEntryBackupTable.entry)
                    .where { 
                        BackupEntryBackupTable.backup neq backup.id and 
                        (BackupEntryBackupTable.entry inList entryIds)
                    }
                    .map { it[BackupEntryBackupTable.entry].value }
                    .toSet()

                val orphanedEntries = backup.entries.filter { it.id !in referencedEntryIds }
                if (orphanedEntries.isNotEmpty()) {
                    val orphanedIds = orphanedEntries.map { it.id }
                    val op = BackupEntryTable.id inList orphanedIds
                    BackupEntryTable.deleteWhere { op }
                    
                    // Delete blobs from disk, but only if the hash is not referenced by any other remaining entry
                    orphanedEntries.forEach { entry ->
                        val hashReferenced = BackupEntryTable
                            .select(BackupEntryTable.id)
                            .where { BackupEntryTable.hash eq entry.hash }
                            .count() > 0
                        if (!hashReferenced) {
                            try {
                                getBlobFile(entry.hash).toFile().delete()
                            } catch (e: Exception) {
                                log.warn("Failed to delete orphaned blob for hash ${entry.hash}: ${e.message}")
                            }
                        }
                    }
                }
            }
            BackupTable.deleteWhere { id eq backup.id }
        }
    }

    override fun getBlobFile(hash: String): Path {
        return blobDir.resolve(hash.take(2)).resolve(hash.drop(2)).createParentDirectories()
    }

    internal suspend fun getBackupInternal(id: Int): Backup? = dbQuery {
        val row = BackupTable.selectAll().where { BackupTable.id eq id }.firstOrNull() ?: return@dbQuery null
        val entryRows = (BackupEntryBackupTable innerJoin BackupEntryTable)
            .select(BackupEntryTable.id, BackupEntryTable.path, BackupEntryTable.size, BackupEntryTable.zippedSize, BackupEntryTable.lastModified, BackupEntryTable.isDirectory, BackupEntryTable.hash, BackupEntryTable.compress)
            .where { BackupEntryBackupTable.backup eq id }
            .toList()
        
        Backup(
            id,
            row[BackupTable.size],
            row[BackupTable.zippedSize],
            row[BackupTable.created],
            row[BackupTable.comment],
            entryRows.map { it.toBackupEntry() },
            row[BackupTable.temporary],
            row[BackupTable.cloudBackupUrl],
            row[BackupTable.metadata]
        )
    }

    override fun getBackup(id: Int): IBackup? = runBlocking { getBackupInternal(id) }

    fun shouldIgnore(file: File): Boolean {
        // todo: '**' pattern
        for (pattern in ignoredFiles) {
            if ('*' !in pattern) {
                if (file.name == pattern) {
                    log.debug("Ignoring file {}, because it matches {}", file, pattern)
                    return true
                }
            } else {
                if (file.name.matches(Regex(pattern.replace("*", ".*")))) {
                    log.debug("Ignoring file {}, because it matches {}", file, pattern)
                    return true
                }
            }
        }
        return false
    }

    /**
     * Restore backup to target directory
     *
     * @param id Backup ID
     * @param target Target directory
     * @param ignored Predicate to ignore files, this prevents files from being deleted,
     * usually should be opposite of the predicate used in [createBackup]
     */
    override suspend fun restore(id: Int, target: Path, ignored: (Path) -> Boolean) = dbQuery {
        val backup = getBackupInternal(id) ?: error("Backup not found")
        val map = backup.entries.associateBy { it.path }.filter { !ignored(Path(it.key)) }
        for (it in target.normalize().toFile().walk().drop(1)) {
            val path = target.normalize().relativize(it.toPath()).normalize()
            if (shouldIgnore(it) || ignored(path))
                continue
            val entry = map[path.toString()]
            if (entry == null && it.isFile) {
                log.info("[X Backup] Deleting $path, not found in backup")
                it.delete()
            }
            if (entry != null && entry.isDirectory != it.isDirectory) {
                log.info("[X Backup] Deleting $path, directory mismatch")
                it.deleteRecursively()
            }
        }
        val done = atomic(0)
        val verbose = map.size < 100
        log.info("[X Backup] ${map.size} files to restore")
        Path("debug-restore.json").writeText(Json.encodeToString(map.keys.toList()))
        val limit = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        val restoreDispatcher = Dispatchers.IO.limitedParallelism(limit)
        val deferredList = map.map {
            this@BackupDatabaseService.async(restoreDispatcher) {
                var worked = false
                val path = target.resolve(it.key).normalize().createParentDirectories()
                if (it.value.lastModified != path.toFile().lastModified() || it.value.size != path.fileSize()) {
                    try {
                        retry(5) {
                            if (it.value.isDirectory) {
                                path.toFile().mkdirs()
                                path.toFile().setLastModified(it.value.lastModified)
                            }
                            else {
                                if (!path.exists()) {
                                    path.createParentDirectories().createFile()
                                }
                                val blob = getBlobFile(it.value.hash)
                                path.outputStream().buffered().use { output ->
                                    val input = it.value.getInputStreamInternal(this@BackupDatabaseService)
                                    if (input == null) {
                                        log.error("Blob not found for file ${it.key}, hash: ${it.value.hash}")
                                        return@async
                                    }
                                    // copy
                                    input.use {
                                        it.copyTo(output)
                                    }
                                }
                                 val fileBytes = path.toFile().readBytes()
                                 
                                 // Check using SHA-256 (default)
                                 val shaHasher = MessageDigest.getInstance("SHA-256")
                                 shaHasher.update(fileBytes)
                                 val checkAgain = shaHasher.digest().joinToString("") { "%02x".format(it) }
                                 
                                 if (checkAgain != it.value.hash) {
                                     val decompressedStream = when (it.value.compress) {
                                         3 -> com.github.luben.zstd.ZstdInputStream(blob.toFile().inputStream().buffered())
                                         4 -> net.jpountz.lz4.LZ4BlockInputStream(blob.toFile().inputStream().buffered())
                                         else -> blob.toFile().inputStream().buffered()
                                     }
                                     val bytes = decompressedStream.use { stream -> stream.readBytes() }
                                     
                                     val shaHasherExpected = MessageDigest.getInstance("SHA-256")
                                     shaHasherExpected.update(bytes)
                                     val expectedHash = shaHasherExpected.digest().joinToString("") { "%02x".format(it) }
                                     
                                     log.error(
                                         "File hash mismatch, file: $path, expected: ${it.value.hash}, actual: $checkAgain, decompressed: $expectedHash" +
                                                 if (it.value.hash == expectedHash && expectedHash != checkAgain) " (writing file failed?)"
                                                 else if (it.value.hash != expectedHash && expectedHash == checkAgain) " (bad hash when creating backup?)"
                                                 else " (WTF???)"
                                     )
                                     path.writeBytes(bytes)
                                 }
                                require(path.fileSize() == it.value.size) {
                                    "File size mismatch, file: $path, expected: ${it.value.size}, actual: ${path.fileSize()}"
                                }
                                path.toFile().setLastModified(it.value.lastModified)
                                worked = true
                            }
                        }
                    } catch (e: RuntimeException) {
                        if (it.key.endsWith("_old")) {
                            log.info("Failed to restore $path, but it's an backup file, ignoring")
                        }
                        else {
                            log.error("Max retry exceeded, file: $path", e)
                        }
                        log.info("worked: $worked, DB lastModified: ${it.value.lastModified}, DB size: ${it.value.size}")
                    }
                }
                val doneNow = done.incrementAndGet()
                activeTaskProgress = 100 * doneNow / map.size
                if (verbose || doneNow % 30 == 0 && worked) {
                    log.info("[X Backup] Restored $done files // current: ${it.key}")
                }
            }
        }
        val reportJob = this@BackupDatabaseService.launch {
            while (done.value < map.size) {
                delay(5000)
                log.info("[X Backup] Restored ${done.value}/${map.size} files")
            }
        }
        deferredList.awaitAll()
        reportJob.cancelAndJoin()
        log.info("Restored backup $id")
    }

    override fun restoreBackup(backup: IBackup, target: Path) = runBlocking {
        restore(backup.id, target) { false }
    }

    /**
     * Check if this backup is valid
     */
    override fun check(backup: IBackup): Boolean {
        var valid = true
        val checkedHashes = HashSet<String>()
        backup.entries.forEach {
            if (it.isDirectory) return@forEach
            if (!checkedHashes.add(it.hash)) return@forEach
            
            val blobFile = getBlobFile(it.hash)
            if (!blobFile.exists()) {
                log.error("Blob not found for file ${it.path}, hash: ${it.hash}")
                valid = false
            }
            else if (blobFile.fileSize() != it.zippedSize) {
                log.error("Blob size mismatch for file ${it.path}, expected: ${it.zippedSize}, actual: ${blobFile.fileSize()}")
                valid = false
            }
        }
        return valid
    }

    private suspend fun packFiles(blobs: List<BackupEntry>): String {
        val stream = ByteArrayOutputStream()
        ZipOutputStream(stream).use { zip ->
            zip.setLevel(9)
            zip.setComment("X-Backup")

            blobs.forEach {
                require(it.compress != 2) { "already packed" }
                zip.putNextEntry(
                    ZipEntry(it.path.replace('\\', '/').trim('/')).apply {
                        creationTime = FileTime.fromMillis(it.lastModified)
                        lastModifiedTime = FileTime.fromMillis(it.lastModified)
                        comment = buildJsonObject {
                            put("hash", it.hash)
                            put("id", it.id)
                        }.toString()
                    })
                val input = requireNotNull(it.getInputStreamInternal(this)) {
                    "Blob not found for file ${it.path}, hash: ${it.hash}"
                }
                input.copyTo(zip)
                input.close()
            }
        }
        val hasher = MessageDigest.getInstance("SHA-256")
        hasher.update(stream.toByteArray())
        val sha256 = hasher.digest().joinToString("") { "%02x".format(it) }
        val file = getBlobFile(sha256)
        if (!file.exists()) {
            file.createParentDirectories().createFile()
            withContext(Dispatchers.IO) {
                stream.writeTo(file.outputStream())
            }
        }
        return sha256
    }

    suspend fun packBackup(backup: Backup) {
        dbQuery {
            val candidateEntries = backup.entries.filter {
                !it.isDirectory && it.size < 1024 * 1024 * 50 // 50MB
            }
            if (candidateEntries.isEmpty()) return@dbQuery
            
            val entryIds = candidateEntries.map { it.id }
            val referencedEntryIds = BackupEntryBackupTable
                .select(BackupEntryBackupTable.entry)
                .where { 
                    BackupEntryBackupTable.backup neq backup.id and 
                    (BackupEntryBackupTable.entry inList entryIds)
                }
                .map { it[BackupEntryBackupTable.entry].value }
                .toSet()

            val entryList = candidateEntries.filter { it.id !in referencedEntryIds }
            if (entryList.size < 10) return@dbQuery
            val packed = packFiles(entryList)
            val blobSize = getBlobFile(packed).fileSize()
            syncDbQuery {
                val ids = entryList.map { it.id }
                BackupEntryTable.update({ BackupEntryTable.id inList ids }) {
                    it[compress] = 2
                    it[cloudDriveId] = 0
                    it[hash] = packed
                    it[zippedSize] = blobSize
                }
            }
        }
    }

    override fun zipArchive(outputStream: ZipOutputStream, backup: IBackup) {
        activeTask = "Zipping backup #${backup.id}"
        var done = 0
        backup.entries.forEach {
            if (!it.isDirectory) {
                outputStream.putNextEntry(ZipEntry(it.path).apply {
                    comment = buildJsonObject {
                        put("hash", it.hash)
                        put("id", it.id)
                    }.toString()
                })
                val input = requireNotNull(it.getInputStream(this)) {
                    "Blob not found for file ${it.path}, hash: ${it.hash}"
                }
                input.copyTo(outputStream, 65536)
                input.close()
            }
            done++
            activeTaskProgress = 100 * done / backup.entries.size
        }
    }

    suspend fun importZipArchive(inputStream: ZipInputStream) {
        val entries = mutableListOf<BackupEntry>()
        while (true) {
            val entry = inputStream.nextEntry ?: break
            val comment = entry.comment ?: continue
            val json = Json.parseToJsonElement(comment).jsonObject
            val hash = json["hash"]!!.jsonPrimitive.content
            val id = json["id"]!!.jsonPrimitive.int
            val path = entry.name
            val size = entry.size
            val lastModified = entry.lastModifiedTime.toMillis()
            val isDirectory = entry.isDirectory
            val shouldCompress = size > 1024
            val blobFile = getBlobFile(hash)
            if (!blobFile.exists()) {
                if (shouldCompress) {
                    wrapOutputStream(blobFile.outputStream().buffered()).use { output ->
                        inputStream.copyTo(output, 65536)
                    }
                } else {
                    blobFile.outputStream().use { output ->
                        inputStream.copyTo(output, 65536)
                    }
                }
            }
            val finalZippedSize = if (shouldCompress) blobFile.fileSize() else size
            entries.add(
                BackupEntry(
                    id,
                    path,
                    size,
                    finalZippedSize,
                    lastModified,
                    isDirectory,
                    hash,
                    if (shouldCompress) {
                        if (config.compressionAlgorithm == Config.CompressionAlgorithm.LZ4) 4 else 3
                    } else 0
                )
            )
        }
    }

    suspend fun deleteUnusedBlobs(): Int {
        val used = dbQuery {
            BackupEntryTable.select(BackupEntryTable.hash)
                .withDistinct(true)
                .map { row -> row[BackupEntryTable.hash] }
        }.toSet()
        val unused = blobDir.toFile().walk().filter { it.isFile }.filter { file ->
            file.parentFile.name.length == 2 && file.parentFile.parentFile == blobDir.toFile()
        }.filterNot { file ->
            val hash = file.parentFile.name + file.name
            hash in used
        }.toList()
        log.info("Deleting ${unused.size} unused blobs")
        unused.forEach {
            it.delete()
        }
        log.info("Deleted ${unused.size} unused blobs")
        return unused.size
    }

    suspend fun clearDatabase() {
        dbQuery {
            SchemaUtils.drop(BackupEntryBackupTable, BackupTable, BackupEntryTable)
            SchemaUtils.createMissingTablesAndColumns(
                BackupEntryTable,
                BackupTable,
                BackupEntryBackupTable,
                withLogs = false
            )
        }
    }

    override suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database, statement = block)

    override suspend fun <T> syncDbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(syncExecutor, database, statement = block)

    override fun listBackups(offset: Int, limit: Int): List<Backup> {
        return transaction {
            val backupRows = BackupTable.selectAll()
                .orderBy(BackupTable.id to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong()).toList()

            if (backupRows.isEmpty()) return@transaction emptyList()

            val backupIds = backupRows.map { it[BackupTable.id].value }

            val entryRows = (BackupEntryBackupTable innerJoin BackupEntryTable)
                .select(BackupEntryBackupTable.backup, BackupEntryTable.id, BackupEntryTable.path, BackupEntryTable.size, BackupEntryTable.zippedSize, BackupEntryTable.lastModified, BackupEntryTable.isDirectory, BackupEntryTable.hash, BackupEntryTable.compress)
                .where { BackupEntryBackupTable.backup inList backupIds }
                .toList()

            val entriesByBackupId = entryRows.groupBy(
                keySelector = { it[BackupEntryBackupTable.backup].value },
                valueTransform = { it.toBackupEntry() }
            )

            backupRows.map { row ->
                val id = row[BackupTable.id].value
                val entries = entriesByBackupId[id] ?: emptyList()
                Backup(
                    id,
                    row[BackupTable.size],
                    row[BackupTable.zippedSize],
                    row[BackupTable.created],
                    row[BackupTable.comment],
                    entries,
                    row[BackupTable.temporary],
                    row[BackupTable.cloudBackupUrl],
                    row[BackupTable.metadata]
                )
            }
        }
    }

    suspend fun getLatestBackup(): Backup? = dbQuery {
        val row = BackupTable.selectAll()
            .orderBy(BackupTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull() ?: return@dbQuery null
        val id = row[BackupTable.id].value
        val entryRows = (BackupEntryBackupTable innerJoin BackupEntryTable)
            .select(BackupEntryTable.id, BackupEntryTable.path, BackupEntryTable.size, BackupEntryTable.zippedSize, BackupEntryTable.lastModified, BackupEntryTable.isDirectory, BackupEntryTable.hash, BackupEntryTable.compress)
            .where { BackupEntryBackupTable.backup eq id }
            .toList()
        
        Backup(
            id,
            row[BackupTable.size],
            row[BackupTable.zippedSize],
            row[BackupTable.created],
            row[BackupTable.comment],
            entryRows.map { it.toBackupEntry() },
            row[BackupTable.temporary],
            row[BackupTable.cloudBackupUrl],
            row[BackupTable.metadata]
        )
    }

    override fun backupCount() = transaction {
        BackupTable.selectAll().count().toInt()
    }

    override fun close() {
        syncExecutor.close()
        TransactionManager.closeAndUnregister(database)
    }

    companion object {
        private const val MEMORY_THRESHOLD = 5 * 1024 * 1024 // 5 MB

        private fun ResultRow.toBackupEntry() = BackupEntry(
            this[BackupEntryTable.id].value,
            this[BackupEntryTable.path],
            this[BackupEntryTable.size],
            this[BackupEntryTable.zippedSize],
            this[BackupEntryTable.lastModified],
            this[BackupEntryTable.isDirectory],
            this[BackupEntryTable.hash],
            this[BackupEntryTable.compress].toInt(),
        )
    }
}
