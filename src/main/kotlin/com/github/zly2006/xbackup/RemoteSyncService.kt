package com.github.zly2006.xbackup

import com.github.zly2006.xbackup.Utils.broadcast
import net.minecraft.server.MinecraftServer
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlin.io.path.*

object RemoteSyncService {
    private val log = LoggerFactory.getLogger("XBackup-RemoteSync")

    enum class RemoteType {
        GIT, FILE
    }

    fun detectRemoteType(url: String): RemoteType {
        val u = url.trim().lowercase()
        return if (u.startsWith("git@") || u.startsWith("http://") || u.startsWith("https://") || u.startsWith("ssh://") || u.endsWith(".git")) {
            RemoteType.GIT
        } else {
            RemoteType.FILE
        }
    }

    // Shared progress tracking properties
    val totalBytesToSync = AtomicLong(0)
    val processedBytes = AtomicLong(0)
    val totalFilesToSync = AtomicInteger(0)
    val processedFiles = AtomicInteger(0)
    @Volatile
    var isSyncActive = false
    @Volatile
    var activeSyncTask = "Idle"

    suspend fun syncToRemote(
        backupId: Int,
        comment: String,
        worldPath: Path,
        service: BackupDatabaseService,
        config: Config,
        server: MinecraftServer?
    ) = withContext(Dispatchers.IO) {
        if (isSyncActive) {
            log.warn("Remote sync is already in progress, skipping duplicate sync request.")
            return@withContext
        }
        val remoteUrl = config.remoteConfig.remoteUrl.trim()
        if (remoteUrl.isEmpty()) {
            return@withContext
        }
        
        isSyncActive = true
        activeSyncTask = "Preparing Remote Sync"
        totalBytesToSync.set(0)
        processedBytes.set(0)
        totalFilesToSync.set(0)
        processedFiles.set(0)
        
        // Progress logger ticker coroutine
        val progressReporter = launch {
            val startTime = System.currentTimeMillis()
            delay(10000) // If it took longer than 10 seconds
            while (isActive && isSyncActive) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val totalBytes = totalBytesToSync.get()
                val processed = processedBytes.get()
                val totalFiles = totalFilesToSync.get()
                val doneFiles = processedFiles.get()
                
                val percent = if (totalFiles > 0) (doneFiles * 100 / totalFiles) else 0
                val processedStr = sizeToString(processed)
                val totalStr = sizeToString(totalBytes)
                
                server?.broadcast(
                    Component.literal("Remote Upload Progress: $percent% ($processedStr / $totalStr) [$activeSyncTask] // Time elapsed: ${elapsed}s")
                )
                delay(config.progressLogInterval.coerceAtLeast(1) * 1000L)
            }
        }
        
        try {
            val remoteType = detectRemoteType(remoteUrl)
            log.info("Starting remote sync to $remoteUrl (Type: $remoteType)")
            
            if (remoteType == RemoteType.FILE) {
                activeSyncTask = "Syncing Files to Directory"
                val targetDir = Path(remoteUrl).absolute().normalize()
                
                // Failsafe directory writable test
                if (!service.verifyDirectoryWritable(targetDir)) {
                    throw IOException("Remote directory '$targetDir' is not writable or disconnected!")
                }
                
                val localBlobDir = service.blobDir
                val remoteBlobDir = targetDir.resolve("blob")
                
                val filesToCopy = localBlobDir.toFile().walk().filter { it.isFile }.toList()
                totalFilesToSync.set(filesToCopy.size)
                totalBytesToSync.set(filesToCopy.sumOf { it.length() })
                processedFiles.set(0)
                processedBytes.set(0)
                
                filesToCopy.forEach { file ->
                    val relativePath = localBlobDir.relativize(file.toPath())
                    val targetFile = remoteBlobDir.resolve(relativePath)
                    if (!targetFile.exists() || targetFile.fileSize() != file.length()) {
                        targetFile.createParentDirectories()
                        Files.copy(file.toPath(), targetFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    processedBytes.addAndGet(file.length())
                    processedFiles.incrementAndGet()
                }
                
                // Copy database
                activeSyncTask = "Syncing Database"
                val dbFile = worldPath.resolve("x_backup.db")
                if (dbFile.exists()) {
                    val targetDb = targetDir.resolve("x_backup.db")
                    Files.copy(dbFile, targetDb, StandardCopyOption.REPLACE_EXISTING)
                }
                
                log.info("Successfully synced backup #$backupId to remote directory: $remoteUrl")
                server?.broadcast(
                    Component.literal("Successfully synced backup #$backupId to remote directory: $remoteUrl")
                )
            } else {
                // GIT REMOTE SYNC
                activeSyncTask = "Git Syncing"
                
                // Check if git is installed
                if (!isGitInstalled()) {
                    val err = "Git CLI is not installed on the system. Cannot push remote backup."
                    log.error(err)
                    server?.broadcast(Component.literal(err))
                    return@withContext
                }
                
                val stagingDir = Path("xb.remote").absolute().normalize()
                if (!stagingDir.exists()) {
                    stagingDir.createDirectories()
                }
                
                val dotGit = stagingDir.resolve(".git")
                if (!dotGit.exists()) {
                    activeSyncTask = "Git Init"
                    runGitCommand(stagingDir, listOf("init"))
                    runGitCommand(stagingDir, listOf("remote", "add", "origin", remoteUrl))
                    runGitCommand(stagingDir, listOf("branch", "-M", config.remoteConfig.gitBranch))
                } else {
                    activeSyncTask = "Git Remote Update"
                    runGitCommand(stagingDir, listOf("remote", "set-url", "origin", remoteUrl))
                    runGitCommand(stagingDir, listOf("branch", "-M", config.remoteConfig.gitBranch))
                }
                
                // Mirror local blobs to staging xb.remote/blob/
                activeSyncTask = "Mirroring Blobs to Git Staging"
                val localBlobDir = service.blobDir
                val stagingBlobDir = stagingDir.resolve("blob")
                
                val filesToCopy = localBlobDir.toFile().walk().filter { it.isFile }.toList()
                totalFilesToSync.set(filesToCopy.size)
                totalBytesToSync.set(filesToCopy.sumOf { it.length() })
                processedFiles.set(0)
                processedBytes.set(0)
                
                filesToCopy.forEach { file ->
                    val relativePath = localBlobDir.relativize(file.toPath())
                    val targetFile = stagingBlobDir.resolve(relativePath)
                    if (!targetFile.exists() || targetFile.fileSize() != file.length()) {
                        targetFile.createParentDirectories()
                        Files.copy(file.toPath(), targetFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    processedBytes.addAndGet(file.length())
                    processedFiles.incrementAndGet()
                }
                
                // Copy database
                activeSyncTask = "Syncing Database to Git Staging"
                val dbFile = worldPath.resolve("x_backup.db")
                if (dbFile.exists()) {
                    val targetDb = stagingDir.resolve("x_backup.db")
                    Files.copy(dbFile, targetDb, StandardCopyOption.REPLACE_EXISTING)
                }
                
                // Run Git commands
                activeSyncTask = "Git Add"
                runGitCommand(stagingDir, listOf("add", "."))
                
                activeSyncTask = "Git Commit"
                val commitMsg = "Backup #$backupId - $comment"
                runGitCommand(stagingDir, listOf("commit", "-m", commitMsg))
                
                activeSyncTask = "Git Push"
                val pushArgs = mutableListOf("push", "-u", "origin", config.remoteConfig.gitBranch)
                if (config.remoteConfig.forcePush) {
                    pushArgs.add("--force")
                }
                val pushResult = runGitCommand(stagingDir, pushArgs)
                if (pushResult.exitCode != 0) {
                    val err = "Git push failed with exit code ${pushResult.exitCode}. Error output: ${pushResult.output}"
                    log.error(err)
                    server?.broadcast(Component.literal("Remote Sync Error: Git push failed! Check logs. ($err)"))
                    throw IOException(err)
                }
                
                log.info("Successfully pushed backup #$backupId to git remote: $remoteUrl (branch: ${config.remoteConfig.gitBranch})")
                server?.broadcast(
                    Component.literal("Successfully pushed backup #$backupId to git remote: $remoteUrl (branch: ${config.remoteConfig.gitBranch})")
                )
            }
        } catch (e: Exception) {
            log.error("Failed to perform remote sync", e)
            server?.broadcast(Component.literal("Remote Sync failed: ${e.message}"))
        } finally {
            isSyncActive = false
            progressReporter.cancel()
        }
    }

    private fun isGitInstalled(): Boolean {
        return try {
            val process = ProcessBuilder("git", "--version").start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun runGitCommand(dir: Path, args: List<String>): ProcessResult {
        val pb = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
        // Disable git terminal prompt hanging
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        pb.environment()["GIT_ASKPASS"] = "true"
        pb.environment()["SSH_ASKPASS"] = "true"
        
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, output)
    }

    private fun sizeToString(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format("%.2f%sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private class ProcessResult(val exitCode: Int, val output: String)
}
