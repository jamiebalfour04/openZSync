package jamiebalfour.zsync;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line frontend for the reusable {@link openZSync}.
 *
 * <p>This class is deliberately limited to parsing arguments, displaying
 * results, and translating outcomes into process exit codes. All filesystem
 * behaviour belongs to {@code DirectorySynchroniser}, allowing other programs
 * to reuse the engine without invoking this command-line layer.</p>
 */
public final class Main {
  /** Utility entry-point class; it is never instantiated. */
  private Main() {
  }

  /**
   * Starts the command-line application.
   *
   * @param arguments options followed by source and destination paths
   */
  public static void main(String[] arguments) {
    System.out.println();
    System.out.println("Welcome to openZSync " + openZSync.getVersion());
    System.out.println();
    System.exit(run(arguments));
  }

  /**
   * Parses and executes a command without terminating the JVM directly.
   * Keeping this separate from {@link #main(String[])} makes argument handling
   * straightforward to test.
   *
   * @param arguments command-line arguments
   * @return 0 on success, 1 for per-entry failures, 2 for bad arguments, or
   *     3 when the operation cannot be started
   */
  static int run(String[] arguments) {
    if (arguments.length == 1 && "--install".equals(arguments[0])) {
      try {
        ZSyncInstaller.install();
        return 0;
      } catch (IOException exception) {
        System.err.println("zsync: installation failed: " + exception.getMessage());
        return 3;
      }
    }

    SynchronisationOptions options = new SynchronisationOptions();
    List<String> paths = new ArrayList<>();

    try {
      // Options may occur before, after, or between the two positional paths.
      // Each --exclude consumes the following argument as its pattern.
      for (int index = 0; index < arguments.length; index++) {
        String argument = arguments[index];
        if ("--dry-run".equals(argument) || "-n".equals(argument)) {
          options.setDryRun(true);
        } else if ("--verify".equals(argument)) {
          options.setVerifyHashes(true);
        } else if ("--delete".equals(argument)) {
          options.setDeleteExtra(true);
        } else if ("--verbose".equals(argument) || "-v".equals(argument)) {
          options.setVerbose(true);
        } else if ("--exclude".equals(argument)) {
          if (++index >= arguments.length) {
            return argumentError("--exclude requires a pattern.");
          }
          options.addExclusion(arguments[index]);
        } else if ("--help".equals(argument) || "-h".equals(argument)) {
          printHelp();
          return 0;
        } else if (argument.startsWith("-")) {
          return argumentError("Unknown option: " + argument);
        } else {
          paths.add(argument);
        }
      }
    } catch (IllegalArgumentException exception) {
      return argumentError(exception.getMessage());
    }

    if (paths.size() != 2) {
      return argumentError("A source and destination directory are required.");
    }

    Path source;
    Path destination;
    try {
      // Parsing paths separately lets us report malformed platform paths as
      // usage errors before touching the filesystem.
      source = Paths.get(paths.get(0));
      destination = Paths.get(paths.get(1));
    } catch (InvalidPathException exception) {
      return argumentError("Invalid path: " + exception.getInput());
    }

    try {
      SynchronisationResult result = openZSync.synchronise(source, destination, options);
      printResult(result, options);
      return result.isSuccessful() ? 0 : 1;
    } catch (IOException | IllegalArgumentException exception) {
      System.err.println("zsync: " + exception.getMessage());
      return 3;
    }
  }

  /**
   * Prints verbose actions when requested, followed by a stable summary that
   * is also convenient for shell users to scan.
   */
  private static void printResult(SynchronisationResult result, SynchronisationOptions options) {
    if (options.isVerbose() || options.isDryRun()) {
      for (SynchronisationResult.Action action : result.getActions()) {
        System.out.printf(Locale.ROOT, "%-18s %s%s%n", action.getType().toString().toLowerCase(Locale.ROOT), action.getPath(), action.getMessage() == null ? "" : " — " + action.getMessage());
      }
      System.out.println();
    }

    System.out.println(options.isDryRun() ? "Dry run complete." : "Synchronisation complete.");
    System.out.println("Directories created: " + result.getDirectoriesCreated());
    System.out.println("Files copied:       " + result.getFilesCopied());
    System.out.println("Files updated:      " + result.getFilesUpdated());
    System.out.println("Entries deleted:    " + result.getFilesDeleted());
    System.out.println("Entries skipped:    " + result.getFilesSkipped());
    System.out.println("Failures:           " + result.getFailures());
    System.out.println("Bytes transferred:  " + humanBytes(result.getBytesCopied()));
  }

  /**
   * Formats transferred bytes using binary units.
   *
   * @param bytes non-negative byte count
   * @return compact human-readable byte quantity
   */
  private static String humanBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double kibibytes = bytes / 1024.0;
    if (kibibytes < 1024.0) {
      return String.format(Locale.ROOT, "%.1f KiB", kibibytes);
    }
    return String.format(Locale.ROOT, "%.1f MiB", kibibytes / 1024.0);
  }

  /**
   * Reports invalid usage consistently.
   *
   * @return command-line usage error exit code
   */
  private static int argumentError(String message) {
    System.err.println("zsync: " + message);
    System.err.println("Try 'zsync --help' for usage.");
    return 2;
  }

  /** Prints command synopsis and safe-default behaviour. */
  private static void printHelp() {
    System.out.println("Usage: zsync [options] <source> <destination>");
    System.out.println();
    System.out.println("Synchronise the contents of one local directory into another.");
    System.out.println("Destination-only files are retained unless --delete is supplied.");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --install        Install the zsync command for the current user");
    System.out.println("  --dry-run        Preview changes without modifying files");
    System.out.println("  --verify         Compare equal-sized files using SHA-256");
    System.out.println("  --delete         Delete destination-only files and directories");
    System.out.println("  --exclude GLOB   Exclude a relative path pattern; repeatable");
    System.out.println("  --verbose        List every action");
    System.out.println("  --help           Show this help");
  }

}
