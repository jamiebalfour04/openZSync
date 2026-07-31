package jamiebalfour.zsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronises the contents of one local directory into another.
 *
 * <p>The class contains no command-line or user-interface code and is therefore
 * suitable for reuse by both the command-line application and other Java
 * programs. A run copies new files, replaces changed files, creates missing
 * directories, and records recoverable failures.
 * Destination-only entries are retained unless deletion is explicitly enabled.</p>
 *
 * <p>Directory trees are traversed without following symbolic links. Files are
 * first copied to a temporary sibling and then moved into place so a failed
 * transfer does not leave a partially-written destination file.</p>
 */
public final class ZSync {
  /** Static utility class; instances carry no state and are unnecessary. */
  private ZSync() {
  }

  public static String getVersion() {
    return "2.0";
  }

  /**
   * Synchronises {@code source} into {@code destination}.
   *
   * <p>Most per-entry I/O failures are recorded in the returned result and
   * traversal continues. Exceptions are thrown only when the roots themselves
   * are invalid or the operation cannot be established safely.</p>
   *
   * @param source existing source directory
   * @param destination existing or new destination directory
   * @param options behaviour controls; {@code null} selects safe defaults
   * @return summary counters and a complete action log
   * @throws IOException if root validation or traversal cannot begin
   * @throws IllegalArgumentException if either path is {@code null}
   */
  public static SynchronisationResult synchronise(Path source, Path destination, SynchronisationOptions options) throws IOException {
    if (source == null || destination == null) {
      throw new IllegalArgumentException("Source and destination paths are required.");
    }
    if (options == null) {
      options = new SynchronisationOptions();
    }

    // Normalising once gives every later relative-path comparison a stable root.
    Path sourceRoot = source.toAbsolutePath().normalize();
    Path destinationRoot = destination.toAbsolutePath().normalize();
    validateRoots(sourceRoot, destinationRoot);

    SynchronisationResult result = new SynchronisationResult();
    // When --delete is enabled this catalogue identifies destination entries
    // that have no corresponding source path.
    Set<Path> sourceEntries = new HashSet<>();

    // Anonymous visitors may only capture final or effectively-final values.
    // The local options reference can change above when null defaults are used.
    SynchronisationOptions finalOptions = options;

    if (!Files.exists(destinationRoot) && !options.isDryRun()) {
      Files.createDirectories(destinationRoot);
    }

    // walkFileTree does not follow symbolic links unless FOLLOW_LINKS is
    // explicitly supplied, preventing cycles and traversal outside the root.
    Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
        Path relative = sourceRoot.relativize(directory);
        // The empty relative path represents the source root itself and must
        // never be excluded, otherwise no traversal could take place.
        if (!relative.toString().isEmpty() && finalOptions.isExcluded(relative)) {
          result.record(SynchronisationResult.ActionType.SKIPPED, display(relative), "Excluded directory", 0);
          // Skipping the subtree avoids visiting and logging every child of an
          // excluded directory.
          return FileVisitResult.SKIP_SUBTREE;
        }

        sourceEntries.add(relative);
        Path target = destinationRoot.resolve(relative);
        if (!Files.exists(target)) {
          try {
            if (!finalOptions.isDryRun()) {
              Files.createDirectories(target);
            }
            result.record(SynchronisationResult.ActionType.CREATED_DIRECTORY, display(relative), finalOptions.isDryRun() ? "Would create directory" : "Created directory", 0);
          } catch (IOException exception) {
            recordFailure(result, relative, exception);
            return FileVisitResult.SKIP_SUBTREE;
          }
        } else if (!Files.isDirectory(target)) {
          result.record(SynchronisationResult.ActionType.FAILED, display(relative), "Destination entry is not a directory", 0);
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        Path relative = sourceRoot.relativize(file);
        if (finalOptions.isExcluded(relative)) {
          result.record(SynchronisationResult.ActionType.SKIPPED, display(relative), "Excluded file", 0);
          return FileVisitResult.CONTINUE;
        }

        sourceEntries.add(relative);
        // Symbolic links are deliberately not recreated in this first version.
        // Following them could escape the source tree or create a cycle.
        if (Files.isSymbolicLink(file)) {
          result.record(SynchronisationResult.ActionType.SKIPPED, display(relative), "Symbolic link skipped", 0);
          return FileVisitResult.CONTINUE;
        }

        Path target = destinationRoot.resolve(relative);
        try {
          boolean targetExists = Files.exists(target);
          boolean changed = !targetExists || filesDiffer(file, target, finalOptions);
          if (!changed) {
            result.record(SynchronisationResult.ActionType.SKIPPED, display(relative), "Unchanged", 0);
            return FileVisitResult.CONTINUE;
          }

          if (!finalOptions.isDryRun()) {
            copyAtomically(file, target);
          }
          SynchronisationResult.ActionType action = targetExists ? SynchronisationResult.ActionType.UPDATED : SynchronisationResult.ActionType.COPIED;
          String message = finalOptions.isDryRun() ? (targetExists ? "Would update file" : "Would copy file") : (targetExists ? "Updated file" : "Copied file");
          result.record(action, display(relative), message, attributes.size());
        } catch (IOException exception) {
          recordFailure(result, relative, exception);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exception) {
        // One unreadable entry should not prevent independent files from being
        // synchronised, so record it and continue walking.
        recordFailure(result, sourceRoot.relativize(file), exception);
        return FileVisitResult.CONTINUE;
      }
    });

    if (options.isDeleteExtra() && Files.exists(destinationRoot)) {
      deleteExtraEntries(destinationRoot, sourceEntries, options, result);
    }
    return result;
  }

  /**
   * Verifies that both roots are directories and cannot recursively contain
   * one another.
   *
   * <p>Containment is rejected in both directions. Synchronising into a child
   * would copy generated output back into itself, while deleting extras from a
   * parent could remove the source.</p>
   */
  private static void validateRoots(Path source, Path destination) throws IOException {
    if (!Files.exists(source)) {
      throw new IOException("Source directory does not exist: " + source);
    }
    if (!Files.isDirectory(source)) {
      throw new IOException("Source path is not a directory: " + source);
    }
    if (Files.exists(destination) && !Files.isDirectory(destination)) {
      throw new IOException("Destination path is not a directory: " + destination);
    }

    // Real paths resolve aliases and symbolic filesystem prefixes (for example
    // /var and /private/var on macOS), closing a subtle overlap loophole.
    Path realSource = source.toRealPath();
    Path comparableDestination = resolveRealPath(destination);
    if (realSource.equals(comparableDestination)
        || realSource.startsWith(comparableDestination)
        || comparableDestination.startsWith(realSource)) {
      throw new IOException("Source and destination must be separate, non-overlapping directories.");
    }
  }

  /**
   * Produces a comparable real path even when the final destination does not
   * yet exist. The nearest existing ancestor is resolved first, then the
   * unresolved tail is appended.
   */
  private static Path resolveRealPath(Path path) throws IOException {
    if (Files.exists(path)) {
      return path.toRealPath();
    }

    Path existingAncestor = path;
    while (existingAncestor != null && !Files.exists(existingAncestor)) {
      existingAncestor = existingAncestor.getParent();
    }
    if (existingAncestor == null) {
      return path.toAbsolutePath().normalize();
    }

    Path unresolvedTail = existingAncestor.relativize(path);
    return existingAncestor.toRealPath().resolve(unresolvedTail).normalize();
  }

  /**
   * Determines whether a source file should replace its destination.
   *
   * <p>Different sizes always mean different content. In normal mode an equal
   * size is paired with modification time for a fast metadata-only comparison.
   * Verification mode instead hashes both files, which is slower but avoids
   * timestamp ambiguity.</p>
   */
  private static boolean filesDiffer(Path source, Path destination, SynchronisationOptions options) throws IOException {
    if (!Files.isRegularFile(destination) || Files.size(source) != Files.size(destination)) {
      return true;
    }
    if (options.isVerifyHashes()) {
      return !MessageDigest.isEqual(sha256(source), sha256(destination));
    }
    return Files.getLastModifiedTime(source).toMillis() != Files.getLastModifiedTime(destination).toMillis();
  }

  /**
   * Streams a file through SHA-256 using a fixed-size buffer, avoiding the need
   * to load large files into memory.
   */
  private static byte[] sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available.", exception);
    }
    // 64 KiB keeps I/O efficient without materially increasing memory use.
    byte[] buffer = new byte[64 * 1024];
    try (InputStream input = Files.newInputStream(file)) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return digest.digest();
  }

  /**
   * Copies a file safely by completing the write under a temporary sibling name
   * before replacing the destination.
   *
   * <p>A sibling is used so source and target of the final move are on the same
   * filesystem. Atomic replacement is preferred; filesystems that do not
   * support it fall back to an ordinary replacing move. The temporary file is
   * removed in all failure paths.</p>
   */
  private static void copyAtomically(Path source, Path destination) throws IOException {
    Path parent = destination.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = destination.resolveSibling("." + destination.getFileName() + ".zsync-" + UUID.randomUUID() + ".tmp");
    try {
      Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
      try {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /**
   * Removes destination entries absent from the source catalogue.
   *
   * <p>Entries are ordered deepest-first so files and child directories are
   * removed before their parents. Excluded subtrees are not collected at all,
   * which protects them from deletion.</p>
   */
  private static void deleteExtraEntries(Path destinationRoot, Set<Path> sourceEntries, SynchronisationOptions options, SynchronisationResult result) throws IOException {
    List<Path> destinationEntries = new ArrayList<>();
    Files.walkFileTree(destinationRoot, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
        Path relative = destinationRoot.relativize(directory);
        if (!relative.toString().isEmpty() && options.isExcluded(relative)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        destinationEntries.add(directory);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        Path relative = destinationRoot.relativize(file);
        if (!options.isExcluded(relative)) {
          destinationEntries.add(file);
        }
        return FileVisitResult.CONTINUE;
      }
    });

    // Reverse path order places descendants before their containing directory.
    destinationEntries.sort(Comparator.reverseOrder());
    for (Path entry : destinationEntries) {
      Path relative = destinationRoot.relativize(entry);
      if (relative.toString().isEmpty() || sourceEntries.contains(relative)) {
        continue;
      }
      try {
        if (!options.isDryRun()) {
          Files.deleteIfExists(entry);
        }
        result.record(SynchronisationResult.ActionType.DELETED, display(relative), options.isDryRun() ? "Would delete destination-only entry" : "Deleted extra entry", 0);
      } catch (IOException exception) {
        recordFailure(result, relative, exception);
      }
    }
  }

  /**
   * Converts an exception into a recoverable action-log entry.
   */
  private static void recordFailure(SynchronisationResult result, Path relative, Exception exception) {
    result.record(SynchronisationResult.ActionType.FAILED, display(relative), exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), 0);
  }

  /**
   * Converts a relative path to its user-facing form. The empty path denotes
   * the root and is displayed as a conventional dot.
   */
  private static String display(Path relative) {
    String value = relative == null ? "" : relative.toString();
    return value.isEmpty() ? "." : value;
  }
}
