## 1.3.0
- Dropped support for BLAKE3 hashing algorithm. Standardized exclusively on JVM-native SHA-256 for integrity verification and content-addressing.
- Combined the sequential hashing and compression progress phases into a single unified real-time progress tracker showing total bytes and files processed.
- Cleaned up restoration verification logic and optimized code efficiency.
- Optimized progress logging.
- Added remote connection pre-verification for Git (using git ls-remote) and directory (absolute path checks) targets before uploading.
- Optimized backup creation database performance

## 1.2.3
- Added remote backup support (Git push and File/Directory mirror copies).
- Added mounted network directory failsafes and configurable absolute blob path directories.
- Added asynchronous progress logging (Percentage, sizes, elapsed time) for operations taking >10 seconds.
- Fixed singleplayer client reopening crashes.
- Excluded `.legacy` files and `chunk_tickets.dat` from backups to prevent size mismatch and locking errors.
- Fixed LZ4 decompression restore fallback mapping bug.

## 1.2.2
- Added coroutine concurrency dispatcher limits for backups and restores.
- Added backup duration logging in console.

## 1.2.1
- Extended the legacy MD5 and compression database migration checks to recursively scan and rename databases (along with their SQLite WAL/SHM files) stored under the `xb.backups/` snapshot directory.
- Updated `/xb delete-all confirm` to fully clean up and delete the `./xb.backups/` snapshot directory from the instance.
- Fixed scheduled backup log spam by adding a state check that ensures the "Skipping scheduled backup..." notice is logged exactly once per idle period.

## 1.2.0
- Replaced MD5 hashing algorithm with BLAKE3 using Apache Commons Codec, generating 32-byte (64-char hex) checksums for backup deduplication and verification.
- Added automatic detection and migration for legacy MD5 and GZIP/ZIP databases, renaming them (along with any matching WAL/SHM SQLite journal files) to `.legacy` to prevent conflicts.
- Added configuration toggles and YACL GUI options for chat notifications: `"Broadcast backup in chat"` and `"Only broadcast to OP"`.
- Improved error handling in retry loops; transient exceptions (like file changes mid-copy) are logged as warnings and retried, while fatal exceptions abort clean.
- Fixed a `NoClassDefFoundError` by configuring ShadowJar to include and relocate the `commons-codec` dependency correctly.

## 1.1.2
- Replaced custom GFS Keep Policy string map with standard integer configuration fields (Keep Last, Keep Daily, Keep Weekly, Keep Monthly) in both the model and YACL GUI.
- Added detailed explanation and context for temporary backups under the "Temporary Backup Expiry" option in the YACL configuration GUI.

## 1.1.1
- Resolved Windows SQLite database locks during `/xb delete-all` by implementing in-DB drop and recreate schema.
- Fixed Backup Storage Path config to update dynamically at runtime without requiring a server restart.
- Added String option under "Retention & Pruning" in YACL config screen to configure the Grandfather-Father-Son (GFS) keep policy with validation support.

## 1.1.0
- Implemented world saving lock/flush before backups
- Added config to pause automatic backups when no players are active
- Added config to discard empty backups
- Added new YACL config screen fields
- Added legacy database detection and automated backup migration to x_backup.db.legacy
- Fixed LZ4 compression code database conflict
- Added '/xb delete-all' command with warning confirmation

## 1.0.0
- Removed Onedrive support
- Added Support for YACL
- Removed Support for GZIP compression in favor of LZ4 and Zstd
- Moved to branch 26.1.2-renewed. This branch is now separated from just maintaining and focusing on implementing new features and refining the mod.

## 0.4.0
- Updated to 26.1.2. No new features yet.

## 0.3.14
- Refactor restart logic. Auto restart is disabled by default now because of plenty of mod incompatibility.

