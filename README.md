# X Backup

The advanced backup mod for Fabric.

## Advantages

- ⚡️Lightning-Fast Speeds: Utilizes multithreading technology for rapid backup processes, completing tasks in a fraction of the time, **speeding up to 50 times faster**. [^1]
- 💾Space-Efficient: Implements incremental backups and automatically compresses large files, minimizing storage usage.
- 🔄Seamless Restoring: Automatically restarts the server after restoring, allowing for a seamless experience.
- ✨Regional Restoring: Restore only the chunks within a specified range, **Players outside the range will not be affected**.
- 🛡️Flexible Support: Designed to support both servers and clients, providing a versatile solution for all your backup needs.

## Usage

### Creating a Backup

You can use the `/xb create` command to create a new backup. This command saves the current state of the game so you can restore it later. If you want to add a note to the backup, you can add it after the command, for example:

```
/xb create This is my first backup
```

This way, you create a backup with a note.

### Viewing Created Backups

If you want to view the backups you have created, you can use the `/xb list` command. This command lists all the backups and displays the number, creation time, and note of each backup. For example:

```
/xb list
```

This command displays the last 6 backups. If you have many backups, you can click the gray font button to view more backups.

### Restoring a Backup

If you want to restore to a specific backup, you can use the `/xb restore <id>` command, where `<id>` is the number of the backup. For example:

```
/xb restore 1
```

This command restores the game to the state of backup number 1. If you want to restore a specific range, you can use the `--chunk` parameter to specify the coordinates from which to which, for example:

```
/xb restore 1 --chunk 0 0 10 10
```

> [!TIP]
> These are the x/z coordinates of the blocks, do not confuse them with chunk coordinates.

This will restore the game to backup 1, but only restore the area from block coordinates (0, 0) to (10, 10).
### Scheduled Backup Configuration

You can set the automatic backup interval using the `/xb backup-interval <seconds>` command. For example, if you want to automatically back up every 3 hours, you can set it like this:

```
/xb backup-interval 10800
```

This command sets the automatic backup interval to 10,800 seconds (i.e., 3 hours).

#### Scheduler Mode (Realtime vs Gametime)
You can configure the scheduler mode inside the YACL config GUI or via the `"scheduler_mode"` property in the config file:
- **Realtime**: Schedules backups based on real-world clock time elapsed.
- **Gametime**: Schedules backups based on active Minecraft world ticks. Note:
  - 20 Minecraft ticks equal 1 second in game time under normal conditions.
  - Server TPS (Ticks Per Second) directly affects the progress of Gametime scheduling.
  - Absolute world game time is persisted inside the backup's metadata, ensuring the scheduler correctly tracks elapsed game time across server restarts and world reloads.

### Mirror Server Configuration

If you are using a mirror server, you can use the `/mirror` command to synchronize the latest backup from the main server. For example:

First, you need to create a backup on the main server:

```
/xb create
```

Then, in the mirror server configuration, set the main server's file path (the one containing `server.properties`) and `blob_path`, and enable mirror mode:

```json
{
  "mirror_mode": true,   
  "mirror_from": "C:\\MinecraftServer\\My-Server",
  "blob_path": "C:\\MinecraftServer\\My-Server\\blob"
}
```

After that, execute the `/mirror` command. This command will synchronize the latest backup state from the main server to the mirror server. You can also add the `id` parameter to specify the backup number to synchronize:

```
/mirror 1
```

If you want to stop the server and restore the backup, you can use the `--stop` parameter:

```
/mirror --stop
```
### Remote Backup & Sync Configuration

X Backup supports automatically copying or pushing completed backups to a remote target. It supports two remote types:
- **Git Remote**: Automatically pushes backup database and blobs to any git repository (e.g. GitHub, GitLab, self-hosted).
- **File Remote**: Copies backup database and blobs to any directory or local network drive share (UNC path).

You can configure the remote target using the `/xb remote-url-set <remote url>` command. For example:
- Set a Git remote:
  ```
  /xb remote-url-set git@github.com:username/my-mc-backups.git
  ```
- Set a File remote (network share):
  ```
  /xb remote-url-set \\192.168.1.100\Backups\Minecraft
  ```

#### Remote Configurations (YACL GUI & Config File)
Under the **Remote Backup** YACL category or in the `x-backup.config.json` file, you can customize:
- **Enable Remote Sync**: Toggle whether remote synchronization is active.
- **Sync on Backup**: Automatically upload/sync backups to the remote whenever a backup is successfully created.
- **Git Branch Name**: The branch name to push to when using a Git remote (defaults to `main`).
- **Force Overwrite Remote**: Enable to force-push (`git push --force`) to remote. If disabled, pushing conflicts will abort the upload and preserve the remote's existing history.

#### Mounted Network Drive Failsafe
To protect against disconnected network drives or unmapped paths, X Backup performs an automated write-read-delete failsafe test before executing backup operations. If the destination storage path is offline or not writable, the backup is safely aborted to prevent data corruption.

### Progress Logs & Message Templates

For long-running backups or remote uploads (taking longer than 10 seconds), X Backup automatically runs an asynchronous progress logging loop. It broadcasts progress updates to the server chat (based on your chat notification settings) and logs them to the console.

#### Customizable Message Templates
Under the **Message Templates** category in the YACL config screen or inside the `"messages"` object in `x-backup.config.json`, you can customize the start, finish, and progress logging statements for both scheduled and manual backups.

The templates support the following placeholders:
- **General**:
  - `%PL%` — Name of the player who triggered the backup (defaults to "System").
  - `%PL_BY%` — Conveniently formatted as `" by <player>"` (empty for system backups).
  - `%ID%` — ID of the backup.
  - `%TN%` — Name of the current active task (e.g. "Backup" or "Scheduled Backup").
- **Files & Data Sizes**:
  - `%DW%` / `%DP%` — Total size of the world / backup before compression (e.g., `12.50MB`).
  - `%DB%` — Size of the backup after compression.
  - `%FC_SZ%` — Size of newly backed up/changed files.
  - `%FT%` — Total count of files in the backup.
  - `%FC%` — Count of changed files.
  - `%FR%` — Count of reused files.
- **Progress Tracking**:
  - `%BG%` — Current progress percentage (0–100).
  - `%FB%` — Files progress string (e.g., `15 / 50`).
  - `%BD%` — Byte size progress string (e.g., `1.50MB / 10.00MB`).
  - `%TL%` — Elapsed time since task start (in seconds).
  - `%TK%` — Time taken to complete the backup (seconds).
  - `%tk%` — Time taken to complete the backup (milliseconds).
- **System Metrics**:
  - `%R%` — JVM RAM usage of the Minecraft server at the time of the log.
  - `%D%` — Total disk capacity of the storage drive.
  - `%DM%` — Disk space currently used on the system drive.
- **Date & Time Formatting**:
  - `%Y%` / `%y%` — Year with / without century.
  - `%m%` / `%-m%` — Month (zero-padded / decimal).
  - `%d%` / `%e%` — Day of month (zero-padded / space-padded).
  - `%H%` / `%I%` — Hour (00–23 / 01–12).
  - `%M%` — Minute (00–59).
  - `%S%` — Second (00–59).
  - `%p%` — AM/PM indicator.
  - `%z%` / `%Z%` — UTC offset / timezone name.
  - `%a%` / `%A%` — Weekday short / long name.
  - `%b%` / `%B%` — Month short / long name.

### Cleaning Up Unnecessary Backups

The mod also provides an automatic cleanup feature for old backups. You can configure the retention policy in the configuration GUI under "Retention & Pruning" or directly in the configuration file:

```json
{
  "prune": {
    "enabled": false,
    "keep_last": 5,
    "keep_daily": 7,
    "keep_weekly": 4,
    "keep_monthly": 12
  }
}
```

This configuration means:
- **Keep Last**: Keeps the `5` most recent backups regardless of age.
- **Keep Daily**: Keeps the latest backup of each day for the last `7` days.
- **Keep Weekly**: Keeps the latest backup of each week for the last `4` weeks.
- **Keep Monthly**: Keeps the latest backup of each month for the last `12` months.

You can set any weekly/daily/monthly retention setting to `0` to disable that specific rule. At least 1 backup must be kept under `Keep Last` retention.

When `enabled` is `true`, the mod will automatically clean up backups that do not meet the retention policy. Alternatively, you can manually clean up backups using the `/xb prune` command.

### Viewing Backup Information

If you want to view detailed information about a specific backup, you can use the `/xb info <id>` command, for example:

```
/xb info 1
```

This command will display detailed information about backup #1, including backup time, notes, size, etc.

## Credits

- **Original Mod**: Created by [zly2006](https://github.com/zly2006) (Original repository: [x-backup](https://github.com/zly2006/x-backup)).
- **Fork / Renewed Version**: This branch (`26.1.2-renewed`) is a new fork of the original mod, taking on the job to implement new features and refine the mod, not just maintain it.
- Some parts of the GUI of this mod are based on BackupManager by CreeperHost LTD.
