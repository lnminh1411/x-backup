package com.github.zly2006.xbackup.gui

import dev.isxander.yacl3.api.*
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import dev.isxander.yacl3.api.controller.EnumControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import com.github.zly2006.xbackup.XBackup
import com.github.zly2006.xbackup.Config
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

object ConfigGui {
    fun createScreen(parent: Screen): Screen {
        val mc = Minecraft.getInstance()
        val connection = mc.connection
        val isAllowed = mc.level == null || mc.isLocalServer || (connection != null && connection.commands.root.getChild("xb") != null)

        if (!isAllowed) {
            return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("X Backup Config"))
                .category(ConfigCategory.createBuilder()
                    .name(Component.literal("Access Denied"))
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Access Denied"))
                        .description(OptionDescription.of(Component.literal("Only server operators (OPs) can view or modify the backup config on multiplayer servers.")))
                        .binding(false, { false }, {})
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .build())
                .build()
                .generateScreen(parent)
        }

        val config = XBackup.config

        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("X Backup Configuration"))
            .save { XBackup.saveConfig() }
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("General Backup"))
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Automatic Backups"))
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Backup Interval (seconds)"))
                        .description(OptionDescription.of(Component.literal("Time in seconds between automatic backups. Set to 0 to disable.")))
                        .binding(10800, { config.backupInterval }, { config.backupInterval = it })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Config.SchedulerMode>()
                        .name(Component.literal("Scheduler Mode"))
                        .description(OptionDescription.of(Component.literal("Realtime: schedule backups based on system clock.\nGametime: schedule backups based on active Minecraft ticks. Note: server TPS will affect the speed of Gametime scheduling (20 ticks = 1 second at 20 TPS).")))
                        .binding(Config.SchedulerMode.REAL_TIME, { config.schedulerMode }, { config.schedulerMode = it })
                        .controller { opt -> EnumControllerBuilder.create(opt).enumClass(Config.SchedulerMode::class.java) }
                        .build())
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Backup Before Restore"))
                        .description(OptionDescription.of(Component.literal("Automatically create a temporary backup before executing a restore operation.")))
                        .binding(true, { config.backupBeforeRestore }, { config.backupBeforeRestore = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Pause Backups Without Players"))
                        .description(OptionDescription.of(Component.literal("Pause scheduled backups when there are no players on the server.")))
                        .binding(true, { config.pauseAutomaticBackupsWithoutPlayers }, { config.pauseAutomaticBackupsWithoutPlayers = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Discard Empty Backups"))
                        .description(OptionDescription.of(Component.literal("Discard scheduled and manual backups that don't contain any modified or new files.")))
                        .binding(true, { config.discardEmptyBackups }, { config.discardEmptyBackups = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Paths & Exclusions"))
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Backup Storage Path (Blobs)"))
                        .description(OptionDescription.of(Component.literal("Path where all deduplicated backup blobs are stored. Can be relative to the server root or an absolute path (including mounted network drives).")))
                        .binding("blob", { config.blobPath }, { config.blobPath = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Permissions & Operators"))
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Operator Permission Level"))
                        .description(OptionDescription.of(Component.literal("OP level required to execute mod commands and access this GUI in multiplayer (1-4).")))
                        .binding(2, { config.operatorPermissionLevel }, { config.operatorPermissionLevel = it })
                        .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 4).step(1) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Chat Notifications"))
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Broadcast Backup in Chat"))
                        .description(OptionDescription.of(Component.literal("Broadcast backup start, completion, and failure messages in chat.")))
                        .binding(false, { config.broadcastBackupInChat }, { config.broadcastBackupInChat = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Only Broadcast to OP"))
                        .description(OptionDescription.of(Component.literal("Only broadcast backup messages to server operators (OPs).")))
                        .binding(true, { config.onlyBroadcastToOp }, { config.onlyBroadcastToOp = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Retention & Pruning"))
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Enable Auto-Pruning"))
                        .description(OptionDescription.of(Component.literal("Enable automated backup cleanup based on GFS retention policy.")))
                        .binding(false, { config.pruneConfig.enabled }, { config.pruneConfig.enabled = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Temporary Backup Expiry"))
                        .description(OptionDescription.of(Component.literal(
                            "Time to retain temporary backups before they are pruned, e.g. 2d.\n" +
                            "Temporary Backups are backups created automatically before a restoration to ensure recovery " +
                            "if the restore fails or is reverted, and are pruned after this expiry duration."
                        )))
                        .binding("2d", { config.pruneConfig.keepTemporary }, { config.pruneConfig.keepTemporary = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Keep Last Backups"))
                        .description(OptionDescription.of(Component.literal(
                            "Number of most recent backups to keep, regardless of their age.\n" +
                            "Example: 5 means the 5 newest backups will always be kept.\n" +
                            "Note: The minimum keep policy is 1 last backup."
                        )))
                        .binding(5, { config.pruneConfig.keepLast }, { config.pruneConfig.keepLast = it.coerceAtLeast(1) })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt).min(1) }
                        .build())
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Keep Daily Days"))
                        .description(OptionDescription.of(Component.literal(
                            "Number of days to keep one daily backup. Represents the newest backup of each calendar day.\n" +
                            "Example: 7 means one backup per day will be kept for the last 7 days.\n" +
                            "Note: Set to 0 to keep 0 daily backups (disables daily retention)."
                        )))
                        .binding(7, { config.pruneConfig.keepDaily }, { config.pruneConfig.keepDaily = it.coerceAtLeast(0) })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt).min(0) }
                        .build())
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Keep Weekly Weeks"))
                        .description(OptionDescription.of(Component.literal(
                            "Number of weeks to keep one weekly backup. Represents the newest backup of each calendar week.\n" +
                            "Example: 4 means one backup per week will be kept for the last 4 weeks.\n" +
                            "Note: Set to 0 to keep 0 weekly backups (disables weekly retention)."
                        )))
                        .binding(4, { config.pruneConfig.keepWeekly }, { config.pruneConfig.keepWeekly = it.coerceAtLeast(0) })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt).min(0) }
                        .build())
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Keep Monthly Months"))
                        .description(OptionDescription.of(Component.literal(
                            "Number of months to keep one monthly backup. Represents the newest backup of each calendar month.\n" +
                            "Example: 12 means one backup per month will be kept for the last 12 months.\n" +
                            "Note: Set to 0 to keep 0 monthly backups (disables monthly retention)."
                        )))
                        .binding(12, { config.pruneConfig.keepMonthly }, { config.pruneConfig.keepMonthly = it.coerceAtLeast(0) })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt).min(0) }
                        .build())
                    .build())
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Compression Settings"))
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Compression Configuration"))
                    .option(Option.createBuilder<Config.CompressionAlgorithm>()
                        .name(Component.literal("Algorithm"))
                        .description(OptionDescription.of(Component.literal("Compression algorithm used to pack backup files. ZSTD is default, LZ4 is faster but offers less compression.")))
                        .binding(Config.CompressionAlgorithm.ZSTD, { config.compressionAlgorithm }, { config.compressionAlgorithm = it })
                        .controller { opt -> EnumControllerBuilder.create(opt).enumClass(Config.CompressionAlgorithm::class.java) }
                        .build())
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Tiered Level"))
                        .description(OptionDescription.of(Component.literal("Compression level: 1 (fastest/least compressed) to 5 (slowest/most compressed).")))
                        .binding(3, { config.compressionLevel }, { config.compressionLevel = it })
                        .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 5).step(1) }
                        .build())
                    .build())
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Remote Backup"))
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Remote Sync Configuration"))
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Enable Remote Sync"))
                        .description(OptionDescription.of(Component.literal("Enable automatic remote backup sync after a local backup finishes.")))
                        .binding(false, { config.remoteConfig.enabled }, { config.remoteConfig.enabled = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Remote URL / Path"))
                        .description(OptionDescription.of(Component.literal("Git repository URL or local network directory path for remote backups.")))
                        .binding("", { config.remoteConfig.remoteUrl }, { config.remoteConfig.remoteUrl = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Sync on Backup"))
                        .description(OptionDescription.of(Component.literal("Automatically synchronize backups to the remote when a backup is created.")))
                        .binding(true, { config.remoteConfig.syncOnBackup }, { config.remoteConfig.syncOnBackup = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Git Branch Name"))
                        .description(OptionDescription.of(Component.literal("Branch name to push to when using a Git remote.")))
                        .binding("main", { config.remoteConfig.gitBranch }, { config.remoteConfig.gitBranch = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<Boolean>()
                        .name(Component.literal("Force Overwrite Remote"))
                        .description(OptionDescription.of(Component.literal("Force push (git push --force) to remote. WARNING: This can overwrite git history! If disabled, conflict will cancel upload.")))
                        .binding(false, { config.remoteConfig.forcePush }, { config.remoteConfig.forcePush = it })
                        .controller { opt -> TickBoxControllerBuilder.create(opt) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Progress Options"))
                    .option(Option.createBuilder<Int>()
                        .name(Component.literal("Progress Logging Interval"))
                        .description(OptionDescription.of(Component.literal("Interval in seconds to log/broadcast progress for long-running backups/syncs (> 10s).")))
                        .binding(5, { config.progressLogInterval }, { config.progressLogInterval = it.coerceAtLeast(1) })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt).min(1) }
                        .build())
                    .build())
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Message Templates"))
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Backup Start Messages"))
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Scheduled Backup Start"))
                        .description(OptionDescription.of(Component.literal("Message sent when a scheduled backup starts. Placeholders: %PL%, %Y%, %y%, %m%, %-m%, %d%, %e%, %H%, %I%, %M%, %S%, %p%, %z%, %Z%, %a%, %A%, %b%, %B%, %R%, %D%, %DM%")))
                        .binding("Running scheduled backup, please wait...", { config.messageConfig.scheduledBackupStart }, { config.messageConfig.scheduledBackupStart = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Manual Backup Start"))
                        .description(OptionDescription.of(Component.literal("Message sent when a manual backup starts. Placeholders: %PL% (player name), %Y%, %y%, %m%, %-m%, %d%, %e%, %H%, %I%, %M%, %S%, %p%, %z%, %Z%, %a%, %A%, %b%, %B%, %R%, %D%, %DM%")))
                        .binding("%PL% is creating a backup, this may take a while...", { config.messageConfig.manualBackupStart }, { config.messageConfig.manualBackupStart = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Backup Finish Messages"))
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Scheduled Backup Finished"))
                        .description(OptionDescription.of(Component.literal("Message sent when a scheduled backup finishes. Placeholders: %ID% (backup ID), %DP% (total size), %DB% (compressed size), %FC_SZ% (new bytes), %TK% (time taken seconds), %tk% (time taken ms), %FT% (total files), %FC% (changed files), %FR% (reused files), %R%, %D%, %DM%")))
                        .binding("Scheduled backup #%ID% finished, %DP% (%DB% after compression) +%FC_SZ% in %TK%s", { config.messageConfig.scheduledBackupFinished }, { config.messageConfig.scheduledBackupFinished = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Manual Backup Finished"))
                        .description(OptionDescription.of(Component.literal("Message sent when a manual backup finishes. Placeholders: %ID% (backup ID), %PL% (player name), %DP% (total size), %DB% (compressed size), %FC_SZ% (new bytes), %TK% (time taken seconds), %tk% (time taken ms), %FT% (total files), %FC% (changed files), %FR% (reused files), %R%, %D%, %DM%")))
                        .binding("Backup #%ID% by %PL% finished, %DP% (%DB% after compression) +%FC_SZ% in %TK%s", { config.messageConfig.manualBackupFinished }, { config.messageConfig.manualBackupFinished = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(Component.literal("Progress Tickers"))
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Backup Progress Tracker"))
                        .description(OptionDescription.of(Component.literal("Message sent during active backup progress tracking. Placeholders: %TN% (task name), %BG% (progress percent), %BD% (bytes progress), %FB% (files progress), %TL% (elapsed time seconds), %R%, %D%, %DM%")))
                        .binding("%TN% Progress: %BG%% (%BD%, Files: %FB%) // Time elapsed: %TL%s", { config.messageConfig.backupProgress }, { config.messageConfig.backupProgress = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("Other Progress Tracker"))
                        .description(OptionDescription.of(Component.literal("Message sent during active non-backup progress. Placeholders: %TN%, %BG%, %BD%, %TL%, %R%, %D%, %DM%")))
                        .binding("%TN% Progress: %BG%% (%BD%) // Time elapsed: %TL%s", { config.messageConfig.otherProgress }, { config.messageConfig.otherProgress = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .build())
                .build())
            .build()
            .generateScreen(parent)
    }
}
