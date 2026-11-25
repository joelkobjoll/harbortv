# Changelog

All notable changes are listed here. Versions match `VERSION_NAME` / `VERSION_CODE` in `gradle.properties`.

## [Unreleased]

### Added
- GitHub repository with Gradle wrapper, version catalog, CI build and automatic update feed on GitHub Pages.
- Default update feed (`https://joelkobjoll.github.io/harbortv/update.json`) for new installs.
- Release build type signed from CI secrets.

### Changed
- Official builds are now signed with a new release key. Boxes on 1.45 (debug-signed) must uninstall and reinstall once.
- `androidx.core` declared explicitly (it was only available transitively).

## [1.47] (build 48)

- Fix a startup crash when reading display settings on older Fire OS devices.

## [1.46] (build 47)

- Use a bulleted list icon for the Channel Categories shortcut.

## [1.45] (build 46)

- Last build from the original source package (debug-signed).
