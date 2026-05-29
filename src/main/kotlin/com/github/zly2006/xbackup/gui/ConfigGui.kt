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
                        .description(OptionDescription.of(Component.literal("Path where all deduplicated backup blobs are stored. Relative to the server root.")))
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
            .build()
            .generateScreen(parent)
    }
}
