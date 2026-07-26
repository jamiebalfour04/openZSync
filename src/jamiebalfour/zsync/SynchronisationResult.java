package jamiebalfour.zsync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates a complete report for one synchronisation run.
 *
 * <p>The result contains both summary counters and an ordered action log. The
 * same representation is used for real and dry-run operations; action messages
 * distinguish between work that happened and work that would happen.</p>
 */
public final class SynchronisationResult {
  /**
   * Categories used by the action log and summary counters.
   */
  public enum ActionType {
    CREATED_DIRECTORY,
    COPIED,
    UPDATED,
    DELETED,
    SKIPPED,
    FAILED
  }

  /**
   * Immtable description of one decision made by the synchroniser.
   */
  public static final class Action {
    private final ActionType type;
    private final String path;
    private final String message;

    Action(ActionType type, String path, String message) {
      this.type = type;
      this.path = path;
      this.message = message;
    }

    /**
     * @return the category of filesystem operation
     */
    public ActionType getType() {
      return type;
    }

    /**
     * @return path relative to the synchronisation root, or {@code .} for it
     */
    public String getPath() {
      return path;
    }

    /**
     * @return human-readable explanation of the operation or failure
     */
    public String getMessage() {
      return message;
    }
  }

  private int directoriesCreated;
  private int filesCopied;
  private int filesUpdated;
  private int filesDeleted;
  private int filesSkipped;
  private int failures;
  private long bytesCopied;
  /** Ordered operation history, useful for verbose output and programmatic callers. */
  private final List<Action> actions = new ArrayList<>();

  /**
   * Adds an operation to the report and updates its corresponding counter.
   *
   * <p>Only copied and updated files contribute to {@code bytesCopied};
   * directory creation and deletion do not represent transferred content.</p>
   *
   * @param type operation category
   * @param path path relative to the synchronisation root
   * @param message explanation suitable for user-facing output
   * @param bytes number of source bytes involved in a copy or update
   */
  void record(ActionType type, String path, String message, long bytes) {
    actions.add(new Action(type, path, message));
    switch (type) {
      case CREATED_DIRECTORY:
        directoriesCreated++;
        break;
      case COPIED:
        filesCopied++;
        bytesCopied += bytes;
        break;
      case UPDATED:
        filesUpdated++;
        bytesCopied += bytes;
        break;
      case DELETED:
        filesDeleted++;
        break;
      case SKIPPED:
        filesSkipped++;
        break;
      case FAILED:
        failures++;
        break;
      default:
        throw new IllegalArgumentException("Unknown action type: " + type);
    }
  }

  /** @return number of destination directories created or planned */
  public int getDirectoriesCreated() {
    return directoriesCreated;
  }

  /** @return number of new files copied or planned */
  public int getFilesCopied() {
    return filesCopied;
  }

  /** @return number of existing files replaced or planned */
  public int getFilesUpdated() {
    return filesUpdated;
  }

  /** @return number of destination-only entries deleted or planned */
  public int getFilesDeleted() {
    return filesDeleted;
  }

  /** @return number of unchanged, excluded, or symbolic-link entries skipped */
  public int getFilesSkipped() {
    return filesSkipped;
  }

  /** @return number of individual operations that could not be completed */
  public int getFailures() {
    return failures;
  }

  /** @return source bytes copied, or that would be copied during a dry run */
  public long getBytesCopied() {
    return bytesCopied;
  }

  /**
   * @return {@code true} when no individual filesystem operation failed
   */
  public boolean isSuccessful() {
    return failures == 0;
  }

  /**
   * @return immutable, traversal-ordered view of all recorded actions
   */
  public List<Action> getActions() {
    return Collections.unmodifiableList(actions);
  }
}
