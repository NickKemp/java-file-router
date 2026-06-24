# Changelog

## Unreleased

## 1.4.0

### Added

- Plugin version number shown bottom-left of the dialog alongside the action buttons

### Changed

- Files in new packages are no longer skipped — missing package directories are created automatically under the first source root of the selected module during install
- Safety net: new package creation only proceeds if at least one file in a known existing package is also NEW or CHANGED in the same analysis run, confirming the correct module is selected
- Analysis reports new-package files as NEW PKG (with package name) rather than SKIPPED
- Install errors due to package directory creation failure are reported as ERROR

## 1.3.0

### Added

- Show Diffs button (enabled after Analyse finds CHANGED files)
- Opens a picker listing all changed files; selecting one launches IntelliJ's built-in diff viewer showing current project version vs downloaded version

## 1.2.0

### Added

- Module selector now auto-selects the module containing the currently open file

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
