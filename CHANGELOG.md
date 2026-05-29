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

