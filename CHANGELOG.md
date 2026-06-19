# Changelog

## Unreleased

## 1.1.0

### Added

- Analyse button scans checked files and classifies each as NEW, CHANGED, or IDENTICAL
- Install button (replaces Route) is enabled only after Analyse finds files to install
- IDENTICAL files are automatically skipped during install — no unnecessary backups
- Analysis summary shows counts of NEW / CHANGED / IDENTICAL / SKIPPED files

## 1.0.2

### Fixed

- Close button now correctly closes the dialog

## 1.0.1

### Added

- Date filter with From/To fields, Today and Clear buttons
- Select All / Clear All buttons
- Refresh button to reload Downloads folder
- Preview panel shows what will be routed before committing

## 1.0.0

### Added

- Initial release
- Routes .java and .zip files from Downloads to matching source package directories
- Backs up existing files as .bak before overwriting
