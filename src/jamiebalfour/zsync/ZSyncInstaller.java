package jamiebalfour.zsync;

import jamiebalfour.helpers.HelperFunctions;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Installs the ZSync JAR and a small command-line launcher for the current user. */
public final class ZSyncInstaller {
  static final String INSTALL_PATH = HelperFunctions.getAppDataDirectory(
      "jamiebalfour/zsync", System.getProperty("user.home") + "/jb/zsync")
      .getAbsolutePath() + "/";

  private ZSyncInstaller() {
  }

  /** Locates the running JAR and installs it using the current operating system's conventions. */
  public static void install() throws IOException {
    Path jar = getRunningJar();
    boolean windows = isWindows();
    Path installDirectory = getInstallDirectory();
    Path commandDirectory = findCommandDirectory(windows);
    install(jar, installDirectory, commandDirectory, windows);
    System.out.println("Installed ZSync to " + installDirectory.resolve("zsync.jar"));
    System.out.println("Installed command to "
        + commandDirectory.resolve(windows ? "zsync.cmd" : "zsync"));
    System.out.println("Open a new terminal and run: zsync --help");
  }

  /** Performs the actual file creation separately so it can be tested safely. */
  static void install(
      Path sourceJar, Path installDirectory, Path commandDirectory, boolean windows)
      throws IOException {
    Files.createDirectories(installDirectory);
    Files.createDirectories(commandDirectory);
    Path installedJar = installDirectory.resolve("zsync.jar");
    if (!sourceJar.toAbsolutePath().normalize()
        .equals(installedJar.toAbsolutePath().normalize())) {
      Files.copy(sourceJar, installedJar, StandardCopyOption.REPLACE_EXISTING);
    }

    Path launcher = commandDirectory.resolve(windows ? "zsync.cmd" : "zsync");
    String content = windows ? windowsLauncher(installedJar) : unixLauncher(installedJar);
    Files.write(launcher, content.getBytes(StandardCharsets.UTF_8));
    if (!windows) {
      try {
        Files.setPosixFilePermissions(
            launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
      } catch (UnsupportedOperationException exception) {
        if (!launcher.toFile().setExecutable(true, false)) {
          throw new IOException("Could not make the zsync command executable.");
        }
      }
    }
  }

  /** The installer must run from a JAR because that is the file it copies. */
  private static Path getRunningJar() throws IOException {
    try {
      Path path = Paths.get(Main.class.getProtectionDomain().getCodeSource()
          .getLocation().toURI()).toAbsolutePath().normalize();
      if (!Files.isRegularFile(path)
          || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
        throw new IOException("The installer must be run using: java -jar zsync.jar --install");
      }
      return path;
    } catch (URISyntaxException exception) {
      throw new IOException("Could not locate the running ZSync JAR.", exception);
    }
  }

  /** Uses HelperFunctions so every OS follows the same application-data convention. */
  private static Path getInstallDirectory() {
    return Paths.get(INSTALL_PATH);
  }

  /** Selects the first writable PATH directory, preferring one owned by the user. */
  private static Path findCommandDirectory(boolean windows) throws IOException {
    String pathVariable = System.getenv("PATH");
    if (pathVariable == null || pathVariable.trim().isEmpty()) {
      throw new IOException("PATH is empty, so the zsync command cannot be installed.");
    }

    Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
    List<Path> writable = new ArrayList<>();
    String separator = windows ? ";" : ":";
    for (String value : pathVariable.split(java.util.regex.Pattern.quote(separator))) {
      if (value == null || value.trim().isEmpty()) {
        continue;
      }
      try {
        Path directory = Paths.get(value.trim()).toAbsolutePath().normalize();
        if (Files.isDirectory(directory) && Files.isWritable(directory)) {
          writable.add(directory);
        }
      } catch (RuntimeException ignored) {
        // Ignore malformed PATH entries and continue looking for a usable one.
      }
    }
    for (Path directory : writable) {
      if (directory.startsWith(home)) {
        return directory;
      }
    }
    if (!writable.isEmpty()) {
      return writable.get(0);
    }
    throw new IOException(
        "No writable directory was found on PATH. Add a user-writable bin directory and try again.");
  }

  private static String unixLauncher(Path jar) {
    return "#!/bin/sh\nexec java -jar \""
        + jar.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        + "\" \"$@\"\n";
  }

  private static String windowsLauncher(Path jar) {
    return "@echo off\r\njava -jar \""
        + jar.toString().replace("\"", "\"\"")
        + "\" %*\r\n";
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
  }
}
