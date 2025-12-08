package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes ARK CLI commands
 */
public class ArkCommandExecutor {

    private static final Logger LOG = Logger.getInstance(ArkCommandExecutor.class.getName());

    // Pattern to extract changelist ID from "CL N:" line
    private static final Pattern CL_ID_PATTERN = Pattern.compile("^CL (\\d+):$");

    // Pattern to extract head from "Current Changelist: N (Head: N)"
    private static final Pattern HEAD_PATTERN = Pattern.compile("\\(Head:\\s*(\\d+)\\)");

    private final String arkExecutablePath;
    private final File workingDirectory;
    private final String password;

    public ArkCommandExecutor(@NotNull String arkExecutablePath, @Nullable File workingDirectory) {
        this(arkExecutablePath, workingDirectory, null);
    }

    public ArkCommandExecutor(@NotNull String arkExecutablePath, @Nullable File workingDirectory, @Nullable String password) {
        this.arkExecutablePath = arkExecutablePath;
        this.workingDirectory = workingDirectory;
        this.password = password;
    }

    /**
     * Execute an ARK command
     *
     * @param args Command arguments (e.g., ["print", "-cl", "latest"])
     * @return Command output
     * @throws VcsException if command fails
     */
    @NotNull
    public String execute(@NotNull List<String> args) throws VcsException {
        List<String> command = new ArrayList<>();
        command.add(arkExecutablePath);
        command.addAll(args);

        LOG.info("=== ARK COMMAND EXECUTION ===");
        LOG.info("Command: " + command);
        LOG.info("Working directory: " + (workingDirectory != null ? workingDirectory.getAbsolutePath() : "NULL (not set!)"));
        LOG.info("Password configured: " + (password != null && !password.isEmpty() ? "YES" : "NO"));

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDirectory != null) {
                pb.directory(workingDirectory);
                LOG.info("Working directory exists: " + workingDirectory.exists());
                if (workingDirectory.exists()) {
                    File arkDir = new File(workingDirectory, ".ark");
                    LOG.info(".ark directory exists: " + arkDir.exists());
                }
            }
            pb.redirectErrorStream(false);

            LOG.info("Starting process...");
            process = pb.start();

            // Check if this is a command that needs authentication (init command)
            boolean needsAuth = args.size() > 0 && "init".equals(args.get(0));

            // Read stdout in a separate thread to prevent blocking
            StringBuilder stdout = new StringBuilder();
            final Process proc = process;
            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stdout) {
                            if (stdout.length() > 0) {
                                stdout.append("\n");
                            }
                            stdout.append(line);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Error reading stdout: " + e.getMessage());
                }
            });
            stdoutReader.start();

            // Read stderr in a separate thread
            StringBuilder stderr = new StringBuilder();
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderr) {
                            if (stderr.length() > 0) {
                                stderr.append("\n");
                            }
                            stderr.append(line);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Error reading stderr: " + e.getMessage());
                }
            });
            stderrReader.start();

            // Only send password for commands that need authentication
            if (needsAuth && password != null && !password.isEmpty()) {
                LOG.info("Command needs authentication, waiting for password prompt...");
                // Give the process time to start and prompt for password
                Thread.sleep(5000);

                // Only send password if process is still running (waiting for input)
                if (process.isAlive()) {
                    OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream());
                    writer.write(password);
                    writer.write("\n");
                    writer.flush();
                    writer.close();
                    LOG.info("Password sent");
                } else {
                    LOG.info("Process completed without needing password (cached auth)");
                }
            } else {
                // Close stdin immediately for commands that don't need auth
                process.getOutputStream().close();
            }

            // Wait for readers to finish
            stdoutReader.join();
            stderrReader.join();

            int exitCode = process.waitFor();
            LOG.info("Process completed with exit code: " + exitCode);
            LOG.info("Stdout: " + stdout.toString());
            LOG.info("Stderr: " + stderr.toString());

            if (exitCode != 0) {
                String errorMessage = "ARK command failed with exit code " + exitCode;
                if (stderr.length() > 0) {
                    errorMessage += "\nStderr: " + stderr.toString();
                }
                if (stdout.length() > 0) {
                    errorMessage += "\nStdout: " + stdout.toString();
                }
                LOG.error("ARK command failed: " + errorMessage);
                throw new VcsException(errorMessage);
            }

            LOG.info("=== ARK COMMAND SUCCESS ===");
            return stdout.toString();
        } catch (Exception e) {
            LOG.error("Exception executing ARK command: " + e.getMessage(), e);
            if (e instanceof VcsException) {
                throw (VcsException) e;
            }
            throw new VcsException("Failed to execute ARK command: " + command, e);
        } finally {
            // Ensure process is destroyed if still running
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    /**
     * Execute an ARK command (varargs version)
     */
    @NotNull
    public String execute(@NotNull String... args) throws VcsException {
        List<String> argList = new ArrayList<>();
        for (String arg : args) {
            argList.add(arg);
        }
        return execute(argList);
    }

    /**
     * Get the current/latest changelist ID using 'ark print -cl latest'
     * Parses the CL ID from the first line "CL N:"
     *
     * @return The changelist ID as a string (e.g., "3")
     */
    @NotNull
    public String getCurrentChangelistId() throws VcsException {
        String output = execute("print", "-cl", "latest");
        String[] lines = output.trim().split("\\r?\\n");

        // Find the line matching "CL N:"
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher matcher = CL_ID_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }

        throw new VcsException("Could not parse changelist ID from 'ark print -cl latest' output: " + output);
    }

    /**
     * Get changelist details using 'ark print -cl <id>'
     *
     * @param changelistId The changelist ID to retrieve
     * @return Raw output from ark print command
     */
    @NotNull
    public String getChangelistInfo(@NotNull String changelistId) throws VcsException {
        return execute("print", "-cl", changelistId);
    }

    /**
     * Get workspace information using 'ark changes'
     * Can be used to get head changelist from "Current Changelist: N (Head: N)"
     *
     * @return Raw output from ark changes command
     */
    @NotNull
    public String getWorkspaceChanges() throws VcsException {
        return execute("changes");
    }

    /**
     * Get the head changelist ID from workspace changes output
     *
     * @return The head changelist ID
     */
    @NotNull
    public String getHeadFromChanges() throws VcsException {
        String output = getWorkspaceChanges();

        Matcher matcher = HEAD_PATTERN.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new VcsException("Could not parse head changelist from 'ark changes' output: " + output);
    }

    /**
     * Test connection to ARK server using 'ark project-list'
     */
    public void testConnection() throws VcsException {
        execute("project-list");
    }

    /**
     * Initialize an ARK workspace
     *
     * @param email User email for authentication
     * @param host Server host (e.g., "ganymede:9000")
     * @throws VcsException if init fails
     */
    public void initWorkspace(@NotNull String email, @NotNull String host) throws VcsException {
        execute("init", "-email", email, "-host", host);
    }

    /**
     * Get a specific changelist (checkout)
     *
     * @param changelistId Changelist ID to get (can be numeric or "latest")
     * @throws VcsException if get fails
     */
    public void getChangelist(@NotNull String changelistId) throws VcsException {
        // Retry get if there are sync issues
        for (int attempt = 1; attempt <= ArkSettings.MAX_GET_RETRIES; attempt++) {
            try {
                execute("get", "-cl", changelistId);
                return; // Success
            } catch (VcsException e) {
                // Check for retryable errors
                String message = e.getMessage();
                if (message != null && (message.contains("sync") || message.contains("Sync"))
                        && attempt < ArkSettings.MAX_GET_RETRIES) {
                    try {
                        Thread.sleep(ArkSettings.GET_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new VcsException("Interrupted while waiting for sync to complete", ie);
                    }
                } else {
                    throw e; // Rethrow if not a sync issue or out of retries
                }
            }
        }

        throw new VcsException("Get failed: sync did not complete after " + ArkSettings.MAX_GET_RETRIES + " attempts");
    }

    /**
     * Create a tag at a specific changelist
     *
     * @param tagName Name of the tag to create
     * @param changelistId Changelist ID to tag
     * @param projectName Optional project name
     * @throws VcsException if tag creation fails
     */
    public void createTag(@NotNull String tagName, @NotNull String changelistId, @Nullable String projectName) throws VcsException {
        List<String> args = new ArrayList<>();
        args.add("tag-add");
        args.add("-name");
        args.add(tagName);
        args.add("-cl");
        args.add(changelistId);
        if (projectName != null && !projectName.isEmpty()) {
            args.add("-project");
            args.add(projectName);
        }
        execute(args);
    }

    /**
     * List tags, optionally filtered by project or changelist
     *
     * @param projectName Optional project name filter
     * @param changelistId Optional changelist filter
     * @return Raw output from tag-list command
     */
    @NotNull
    public String listTags(@Nullable String projectName, @Nullable String changelistId) throws VcsException {
        List<String> args = new ArrayList<>();
        args.add("tag-list");
        if (projectName != null && !projectName.isEmpty()) {
            args.add("-project");
            args.add(projectName);
        }
        if (changelistId != null && !changelistId.isEmpty()) {
            args.add("-cl");
            args.add(changelistId);
        }
        return execute(args);
    }

    /**
     * Switch to a specific branch
     *
     * @param projectName Project name
     * @param branchName Branch name to switch to
     * @throws VcsException if switch fails
     */
    public void switchBranch(@NotNull String projectName, @NotNull String branchName) throws VcsException {
        execute("switch-branch", "-project", projectName, "-branch", branchName);
    }

    /**
     * List branches for a project
     *
     * @param projectName Project name
     * @return Raw output from branch-list command
     */
    @NotNull
    public String listBranches(@NotNull String projectName) throws VcsException {
        return execute("branch-list", "-project", projectName);
    }

    /**
     * Check if an ARK workspace is initialized in the working directory
     *
     * @return true if workspace exists, false otherwise
     */
    public boolean isWorkspaceInitialized() {
        if (workingDirectory == null || !workingDirectory.exists()) {
            return false;
        }

        // Check if .ark directory exists (workspace marker)
        File arkDir = new File(workingDirectory, ".ark");
        return arkDir.exists() && arkDir.isDirectory();
    }

    /**
     * Ensure workspace is initialized
     *
     * @param email User email
     * @param host Server host
     * @throws VcsException if initialization fails
     */
    public void ensureWorkspaceInitialized(@NotNull String email, @NotNull String host) throws VcsException {
        LOG.info("ensureWorkspaceInitialized called - workingDirectory: " +
                 (workingDirectory != null ? workingDirectory.getAbsolutePath() : "null"));

        if (isWorkspaceInitialized()) {
            LOG.info("Workspace already initialized at: " + workingDirectory.getAbsolutePath());
            return; // Already initialized
        }

        if (workingDirectory == null) {
            throw new VcsException("Cannot initialize workspace: working directory is not configured. " +
                "Please set the 'Working Directory' field in the VCS root settings.");
        }

        // Create working directory if it doesn't exist
        if (!workingDirectory.exists()) {
            LOG.info("Creating working directory: " + workingDirectory.getAbsolutePath());
            if (!workingDirectory.mkdirs()) {
                throw new VcsException("Failed to create working directory: " + workingDirectory.getAbsolutePath());
            }
        }

        // Initialize the workspace
        LOG.info("Initializing ARK workspace at: " + workingDirectory.getAbsolutePath());
        initWorkspace(email, host);
        LOG.info("ARK workspace initialized successfully");
    }

    /**
     * Get the working directory
     */
    @Nullable
    public File getWorkingDirectory() {
        return workingDirectory;
    }
}
