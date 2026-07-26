# ZSync

ZSync stands for **Zippy Synchronisation**. It is a small, independent Java command-line application for keeping two local directories synchronised.

## Installing the command

Build or obtain `zsync.jar`, then run:

```sh
java -jar zsync.jar --install
```

The installer copies the JAR to a stable per-user application directory and creates a `zsync` command in the first suitable writable directory already on `PATH`. On Windows it creates `zsync.cmd`.

Default application-data locations are:

- macOS: `~/Library/Application Support/ZSync/zsync.jar`
- Linux: `~/.local/share/zsync/zsync.jar`
- Windows: `%LOCALAPPDATA%\ZSync\zsync.jar`

The installer does not require administrator privileges. If no writable command directory exists on `PATH`, it explains that a user-writable bin directory must be added first.

After installation, open a new terminal and run:

```sh
zsync --help
```

ZSync, or ZippySync, is a small, dependency-free Java 11 command-line directory synchroniser.
It recursively copies new and changed files from a source directory into a
destination directory while retaining destination-only files by default.

This project first started in 2017 that was going to be built into ZPE. It
has since been extracted into a standalone library that can be used by other
Java programs. I have to thank ChatGPT for a lot of assistance with improving this as
my version was originally very much a mess of code chucked in one big class.

## Build

```sh
chmod +x build.sh zsync
./build.sh
```

The build creates `build/zsync.jar` and runs the temporary-directory test suite.

## Usage

```sh
./zsync [options] <source> <destination>
```

Examples:

```sh
./zsync --dry-run --verbose ~/Documents/source /Volumes/Backup/source
./zsync --verify ~/Documents/source /Volumes/Backup/source
./zsync --delete --exclude ".git/**" source destination
```

Options:

- `-n`, `--dry-run`: preview operations without changing the destination.
- `--verify`: use SHA-256 when comparing equal-sized files.
- `--delete`: remove destination-only files and directories.
- `--exclude GLOB`: exclude a relative path pattern; may be repeated.
- `-v`, `--verbose`: list individual operations.
- `-h`, `--help`: show command help.

## Safety

- Destination-only entries are retained unless `--delete` is explicitly used.
- Identical and overlapping source/destination directories are rejected.
- Symbolic links are not followed and are skipped.
- Files are copied to a temporary sibling and then moved into place.
- Excluded destination entries are protected from `--delete`.

## Embedding

`DirectorySynchroniser` contains the reusable engine. ZPE's future
`directory_synchronise` function can translate YASS options into a
`SynchronisationOptions` instance and convert `SynchronisationResult` into a
ZPE object or map without invoking the command-line layer.
