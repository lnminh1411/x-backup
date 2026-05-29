## 1.1.0
- Implemented world saving lock/flush before backups
- Added config to pause automatic backups when no players are active
- Added config to discard empty backups (with 0 new files)
- Added new YACL config screen fields
- Added legacy database detection and automated backup migration to x_backup.db.legacy
- Fixed LZ4 compression code database conflict (LZ4=4, Zstd=3, Gzip=1, Zip=2)
- Added '/xb delete-all' command with warning confirmation

## 1.0.0
- Removed Onedrive support
- Added Support for YACL
- Removed Support for GZIP compression in favor of 

## 0.4.0
- Updated to 26.1.2. No new features yet.

## 0.3.14
- Refactor restart logic. Auto restart is disabled by default now because of plenty of mod incompatibility.

