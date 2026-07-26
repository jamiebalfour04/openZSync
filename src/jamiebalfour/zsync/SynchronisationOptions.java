package jamiebalfour.zsync;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes the behaviour of a directory synchronisation operation.
 *
 * <p>The defaults are intentionally conservative: files are compared using
 * their size and modification time, destination-only entries are retained,
 * and changes are applied immediately. Setter methods return this object so
 * callers can construct an option set using a fluent style.</p>
 *
 * <p>An instance is configured before synchronisation starts and should not be
 * modified while a synchronisation is running.</p>
 */
public final class SynchronisationOptions {
  /** When true, operations are recorded but the filesystem is not modified. */
  private boolean dryRun;

  /** When true, equal-sized files are compared using SHA-256 rather than time. */
  private boolean verifyHashes;

  /** Whether destination entries that have no source equivalent may be deleted. */
  private boolean deleteExtra;

  /** Whether the command-line frontend should print each individual action. */
  private boolean verbose;

  /** Original patterns retained for inspection by callers. */
  private final List<String> exclusions = new ArrayList<>();

  /** Precompiled matchers avoid reparsing every pattern for every visited path. */
  private final List<PathMatcher> exclusionMatchers = new ArrayList<>();

  /**
   * @return {@code true} when the operation is a non-mutating preview
   */
  public boolean isDryRun() {
    return dryRun;
  }

  /**
   * Selects preview mode.
   *
   * @param dryRun {@code true} to calculate changes without applying them
   * @return this option object
   */
  public SynchronisationOptions setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
    return this;
  }

  public boolean isVerifyHashes() {
    return verifyHashes;
  }

  public SynchronisationOptions setVerifyHashes(boolean verifyHashes) {
    this.verifyHashes = verifyHashes;
    return this;
  }

  public boolean isDeleteExtra() {
    return deleteExtra;
  }

  public SynchronisationOptions setDeleteExtra(boolean deleteExtra) {
    this.deleteExtra = deleteExtra;
    return this;
  }

  public boolean isVerbose() {
    return verbose;
  }

  public SynchronisationOptions setVerbose(boolean verbose) {
    this.verbose = verbose;
    return this;
  }

  /**
   * Adds a glob matched against paths relative to the source or destination
   * root. Backslashes are normalised so that patterns can be supplied in a
   * consistent form on Windows and Unix-like systems.
   *
   * <p>Examples include {@code *.class}, {@code build/**}, and
   * {@code .git/**}. The method is repeatable.</p>
   *
   * @param pattern filesystem glob to exclude
   * @return this option object
   * @throws IllegalArgumentException if the pattern is empty or malformed
   */
  public SynchronisationOptions addExclusion(String pattern) {
    if (pattern == null || pattern.trim().isEmpty()) {
      throw new IllegalArgumentException("An exclusion pattern cannot be empty.");
    }
    String normalised = pattern.replace('\\', '/');
    exclusions.add(normalised);
    exclusionMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + normalised));
    return this;
  }

  /**
   * @return an immutable view of the configured exclusion patterns
   */
  public List<String> getExclusions() {
    return Collections.unmodifiableList(exclusions);
  }

  /**
   * Tests a relative path against the compiled exclusion rules.
   *
   * <p>This method is package-private because matching is an implementation
   * detail shared by the synchronisation engine, not part of the public API.</p>
   *
   * @param relativePath path relative to a synchronisation root
   * @return {@code true} when any configured glob matches
   */
  boolean isExcluded(java.nio.file.Path relativePath) {
    if (relativePath == null) {
      return false;
    }
    // PathMatcher works on Path objects, so reconstruct the path after
    // normalising separators rather than matching a platform-dependent string.
    java.nio.file.Path normalised = FileSystems.getDefault().getPath(relativePath.toString().replace('\\', '/'));
    for (PathMatcher matcher : exclusionMatchers) {
      if (matcher.matches(normalised)) {
        return true;
      }
    }
    return false;
  }
}
