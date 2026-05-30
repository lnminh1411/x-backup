package com.github.zly2006.xbackup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale

@Serializable
class Config {
    @Serializable
    class PruneConfig {
        @SerialName("enabled")
        var enabled = false

        @SerialName("keep_last")
        var keepLast = 5

        @SerialName("keep_daily")
        var keepDaily = 7

        @SerialName("keep_weekly")
        var keepWeekly = 4

        @SerialName("keep_monthly")
        var keepMonthly = 12

        @SerialName("keep_temporary")
        var keepTemporary = "2d"

        fun temporaryKeepPolicy(): Long {
            return keepTemporary.toMillis()
        }

        fun prune(idToTime: Map<String, Long>, now: Long): List<String> {
            if (idToTime.isEmpty()) return emptyList()

            val zoneId = ZoneId.systemDefault()
            val nowZDT = Instant.ofEpochMilli(now).atZone(zoneId)
            val nowDate = nowZDT.toLocalDate()
            val weekFields = WeekFields.of(Locale.getDefault())

            // Sort all backups by timestamp descending (newest first)
            val sortedBackups = idToTime.toList().map { (id, time) ->
                val zdt = Instant.ofEpochMilli(time).atZone(zoneId)
                BackupTimeInfo(id, time, zdt)
            }.sortedByDescending { it.time }

            val keptIds = mutableSetOf<String>()

            // 1. Keep Last L (minimum 1)
            val l = keepLast.coerceAtLeast(1)
            sortedBackups.take(l).forEach { keptIds.add(it.id) }

            // 2. Keep Daily D
            if (keepDaily > 0) {
                val validDays = (0 until keepDaily).map { offset ->
                    nowDate.minusDays(offset.toLong())
                }.toSet()

                val dailyGroups = sortedBackups.filter { it.zdt.toLocalDate() in validDays }
                    .groupBy { it.zdt.toLocalDate() }
                
                for ((_, group) in dailyGroups) {
                    keptIds.add(group.maxByOrNull { it.time }!!.id)
                }
            }

            // 3. Keep Weekly W
            if (keepWeekly > 0) {
                val validWeeks = (0 until keepWeekly).map { offset ->
                    val date = nowDate.minusWeeks(offset.toLong())
                    val y = date.get(weekFields.weekBasedYear())
                    val w = date.get(weekFields.weekOfWeekBasedYear())
                    "$y-W$w"
                }.toSet()

                val weeklyGroups = sortedBackups.filter {
                    val y = it.zdt.get(weekFields.weekBasedYear())
                    val w = it.zdt.get(weekFields.weekOfWeekBasedYear())
                    "$y-W$w" in validWeeks
                }.groupBy {
                    val y = it.zdt.get(weekFields.weekBasedYear())
                    val w = it.zdt.get(weekFields.weekOfWeekBasedYear())
                    "$y-W$w"
                }

                for ((_, group) in weeklyGroups) {
                    keptIds.add(group.maxByOrNull { it.time }!!.id)
                }
            }

            // 4. Keep Monthly M
            if (keepMonthly > 0) {
                val validMonths = (0 until keepMonthly).map { offset ->
                    val date = nowDate.minusMonths(offset.toLong())
                    "${date.year}-M${date.monthValue}"
                }.toSet()

                val monthlyGroups = sortedBackups.filter {
                    "${it.zdt.year}-M${it.zdt.monthValue}" in validMonths
                }.groupBy {
                    "${it.zdt.year}-M${it.zdt.monthValue}"
                }

                for ((_, group) in monthlyGroups) {
                    keptIds.add(group.maxByOrNull { it.time }!!.id)
                }
            }

            val allIds = idToTime.keys
            return allIds.filter { it !in keptIds }
        }

        private data class BackupTimeInfo(val id: String, val time: Long, val zdt: ZonedDateTime)

        private fun String.toMillis(): Long {
            val regex = Regex("(\\d+)([mhdwMy])")
            var startIndex = 0
            var ret = 0L
            while (startIndex < length) {
                val match = regex.find(this, startIndex) ?: break
                val (num, unit) = match.destructured
                val value = num.toLong()
                startIndex = match.range.last + 1
                ret += when (unit) {
                    "m" -> value * 60 * 1000
                    "h" -> value * 60 * 60 * 1000
                    "d" -> value * 24 * 60 * 60 * 1000
                    "w" -> value * 7 * 24 * 60 * 60 * 1000
                    "M" -> value * 30 * 24 * 60 * 60 * 1000
                    "y" -> value * 365 * 24 * 60 * 60 * 1000
                    else -> throw IllegalArgumentException("Unknown unit: $unit")
                }
            }
            return ret
        }
    }

    @Serializable
    class RemoteConfig {
        @SerialName("enabled")
        var enabled = false

        @SerialName("remote_url")
        var remoteUrl = ""

        @SerialName("sync_on_backup")
        var syncOnBackup = true

        @SerialName("git_branch")
        var gitBranch = "main"

        @SerialName("force_push")
        var forcePush = false
    }

    @Serializable
    enum class CompressionAlgorithm {
        @SerialName("zstd") ZSTD,
        @SerialName("lz4") LZ4
    }

    @SerialName("ignored_files")
    val ignoredFiles: List<String> = listOf(
        "session.lock",
        "fake_player.gca.json",
        "ledger.sqlite",
        "**.dat_old",
        "DistantHorizons.sqlite",
        "DistantHorizons.sqlite-shm",
        "DistantHorizons.sqlite-wal",
        "chunk_tickets.dat",
    )

    @SerialName("blob_path")
    var blobPath = "blob"

    @SerialName("backup_interval")
    var backupInterval = 10800

    @SerialName("backup_before_restore")
    var backupBeforeRestore = true

    @SerialName("mirror_mode")
    var mirrorMode: Boolean = false

    @SerialName("mirror_from")
    var mirrorFrom: String? = null

    @SerialName("language")
    var language = "en_us"

    @SerialName("prune")
    val pruneConfig = PruneConfig()

    @SerialName("remote")
    val remoteConfig = RemoteConfig()

    @SerialName("progress_log_interval")
    var progressLogInterval = 5

    @SerialName("compression_algorithm")
    var compressionAlgorithm: CompressionAlgorithm = CompressionAlgorithm.ZSTD

    @SerialName("compression_level")
    var compressionLevel = 3

    @SerialName("operator_permission_level")
    var operatorPermissionLevel = 2

    @SerialName("pause_automatic_backups_without_players")
    var pauseAutomaticBackupsWithoutPlayers = true

    @SerialName("discard_empty_backups")
    var discardEmptyBackups = true

    @SerialName("broadcast_backup_in_chat")
    var broadcastBackupInChat = false

    @SerialName("only_broadcast_to_op")
    var onlyBroadcastToOp = true
}

