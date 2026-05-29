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
                        .description(OptionDescription.of(Component.literal("Time to retain temporary backups before they are pruned, e.g. 2d.")))
                        .binding("2d", { config.pruneConfig.keepTemporary }, { config.pruneConfig.keepTemporary = it })
                        .controller { opt -> StringControllerBuilder.create(opt) }
                        .build())
                    .option(Option.createBuilder<String>()
                        .name(Component.literal("GFS Keep Policy"))
                        .description(OptionDescription.of(Component.literal(
                            "Grandfather-Father-Son (GFS) prune keep policy.\n" +
                            "Format: <window>:<interval>, <window>:<interval>, ...\n" +
                            "Units: m (minutes), h (hours), d (days), w (weeks), M (months), y (years)\n" +
                            "Example: 1d:30m, 1w:6h, 1M:1d, 1y:1w, 2y:1M\n" +
                            "If formatting is invalid, the changes will not be saved."
                        )))
                        .binding(
                            "1d:30m, 1w:6h, 1M:1d, 1y:1w, 2y:1M",
                            { config.pruneConfig.getKeepPolicyString() },
                            { config.pruneConfig.setKeepPolicyString(it) }
                        )
                        .controller { opt -> StringControllerBuilder.create(opt) }
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
