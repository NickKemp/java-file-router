# Changelog

## Unreleased

## 1.0.2 - 2026-06-13

### Changed

- Cancel button renamed to Close

## 1.0.1 - 2026-06-12

### Fixed

- File list now populated on first open without requiring Refresh
- Zip preview now expands to show all contents
- Shows non-routeable file types found in zip
- Font matches IntelliJ editor font

## 1.0.0 - 2026-06-12

### Added

- Route Downloaded Java File action in the Build menu
- Select module from open project then pick .java or .zip files
- Date filter defaults to today — shows only today's downloads
- File list sorted newest first with download date/time displayed
- Checkbox multi-select with Select All / Clear All / Refresh
- Preview panel updates automatically showing ROUTE / BACKUP / SKIP for each file
- Result panel shows full routing log after Route is clicked
- Reads package declaration to locate correct source directory
- Never creates packages — only routes to existing directories
- Renames existing file to .java.bak before copying new version
- Strips browser-appended download suffix e.g. TouchFilesScreen (6).java → TouchFilesScreen.java
- Routes all .java files in a .zip to their correct packages
- Shows zip contents including non-routeable file types
- Refreshes IntelliJ VFS so routed files appear immediately
- Font matches IntelliJ editor font via EditorColorsManager
- Result panel shows full routing log with any errors or skipped files
