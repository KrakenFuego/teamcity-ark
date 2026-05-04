package com.ark.teamcity.agent;

import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.agent.vcs.AgentCheckoutAbility;
import jetbrains.buildServer.agent.vcs.AgentVcsSupport;
import jetbrains.buildServer.agent.vcs.UpdateByCheckoutRules2;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Agent-side VCS support for ARK
 * Handles checkout directly on the build agent using ark CLI
 */
public class ArkAgentVcsSupport extends AgentVcsSupport implements UpdateByCheckoutRules2 {

    private static final int CHECKOUT_VERIFY_RETRIES = 2;
    private static final long CHECKOUT_VERIFY_DELAY_MS = 1000L;

    @NotNull
    @Override
    public UpdatePolicy getUpdatePolicy() {
        return this;
    }

    @NotNull
    @Override
    public String getName() {
        return "ark";  // Must match server-side getName()
    }

    @NotNull
    @Override
    public AgentCheckoutAbility canCheckout(
            @NotNull VcsRoot vcsRoot,
            @NotNull CheckoutRules checkoutRules,
            @NotNull AgentRunningBuild build) {

        // Check if 'ark' command is available on the agent
        String arkExecutablePath = resolveArkExecutablePath(vcsRoot);
        if (!isArkCommandAvailable(arkExecutablePath)) {
            return AgentCheckoutAbility.noVcsClientOnAgent(
                "ARK command not found on agent at '" + arkExecutablePath + "'. " +
                "Please install ARK CLI and configure the OS-specific executable path.");
        }

        build.getBuildLogger().message("ARK: Agent checkout is available");
        return AgentCheckoutAbility.canCheckout();
    }

    private static final String BRANCH_NAME_PREFIX = "refs/heads/";

    public void updateSources(
            @NotNull VcsRoot root,
            @NotNull CheckoutRules checkoutRules,
            @NotNull String toVersion,
            @NotNull File checkoutDirectory,
            @NotNull AgentRunningBuild build,
            boolean cleanCheckout) throws VcsException {

        BuildProgressLogger logger = build.getBuildLogger();
        logger.message("ARK: Updating sources to CL " + toVersion);

        // Get VCS root properties
        String projectName = root.getProperty("projectName");
        String branchName = root.getProperty("branchName");

        // Check for feature branch override from TeamCity
        // TeamCity passes the logical branch name for feature branch builds
        String vcsRootId = root.getVcsName() + "_" + root.getId();
        String featureBranch = build.getSharedConfigParameters().get("teamcity.build.vcs.branch." + vcsRootId);
        if (featureBranch == null || featureBranch.isEmpty()) {
            // Try without the ID suffix
            featureBranch = build.getSharedConfigParameters().get("teamcity.build.branch");
        }

        if (featureBranch != null && !featureBranch.isEmpty()) {
            // Strip refs/heads/ prefix if present (TeamCity sends normalized branch names)
            if (featureBranch.startsWith(BRANCH_NAME_PREFIX)) {
                featureBranch = featureBranch.substring(BRANCH_NAME_PREFIX.length());
            }
            // Only use feature branch if it's different from default
            if (!featureBranch.equals(branchName)) {
                logger.message("ARK: Feature branch detected: " + featureBranch);
                branchName = featureBranch;
            }
        }
        String serverHost = root.getProperty("serverHost");
        String botToken = root.getProperty("secure:userPassword");
        String arkExecutablePath = resolveArkExecutablePath(root);

        if (projectName == null || projectName.trim().isEmpty()) {
            throw new VcsException("Project name not configured in VCS root");
        }
        if (serverHost == null || serverHost.trim().isEmpty()) {
            throw new VcsException("Server host not configured in VCS root");
        }
        if (botToken == null || botToken.trim().isEmpty()) {
            throw new VcsException("Bot token not configured in VCS root");
        }

        logger.message("ARK: Project: " + projectName);
        logger.message("ARK: Branch: " + branchName);
        logger.message("ARK: Server: " + serverHost);
        logger.message("ARK: Target Changelist: " + toVersion);
        logger.message("ARK: Checkout directory: " + checkoutDirectory.getAbsolutePath());
        logger.message("ARK: Clean checkout: " + cleanCheckout);

        File arkDir = new File(checkoutDirectory, ".ark");
        boolean workspaceExists = arkDir.exists() && arkDir.isDirectory();

        if (cleanCheckout) {
            // Clean checkout explicitly requested
            logger.message("ARK: Clean checkout requested - removing existing workspace");

            // Clean directory if it exists
            if (checkoutDirectory.exists()) {
                logger.message("ARK: Cleaning existing directory");
                deleteDirectory(checkoutDirectory, logger);
            }

            // Ensure checkout directory exists
            if (!checkoutDirectory.exists()) {
                logger.message("ARK: Creating checkout directory: " + checkoutDirectory.getAbsolutePath());
                checkoutDirectory.mkdirs();
            }

            // Initialize workspace
            logger.message("ARK: Initializing workspace");
            runArkCommand(checkoutDirectory, logger, arkExecutablePath,
                "init-bot", "-token", botToken, "-host", normalizeHostWithDefaultPort(serverHost));

            // Switch to the correct project and branch
            logger.message("ARK: Switching to project " + projectName + " branch " + branchName);
            runArkCommand(checkoutDirectory, logger, arkExecutablePath,
                "switch-branch", "-project", projectName, "-branch", branchName);

        } else if (!workspaceExists) {
            // First time checkout - workspace doesn't exist yet
            logger.message("ARK: First time checkout - initializing workspace");

            // Clean directory if it exists (TeamCity may have created it but it's not a valid workspace)
            if (checkoutDirectory.exists()) {
                logger.message("ARK: Cleaning existing non-workspace directory");
                deleteDirectory(checkoutDirectory, logger);
            }

            // Ensure checkout directory exists
            if (!checkoutDirectory.exists()) {
                logger.message("ARK: Creating checkout directory: " + checkoutDirectory.getAbsolutePath());
                checkoutDirectory.mkdirs();
            }

            // Initialize workspace
            logger.message("ARK: Initializing workspace");
            runArkCommand(checkoutDirectory, logger, arkExecutablePath,
                "init-bot", "-token", botToken, "-host", normalizeHostWithDefaultPort(serverHost));

            // Switch to the correct project and branch
            logger.message("ARK: Switching to project " + projectName + " branch " + branchName);
            runArkCommand(checkoutDirectory, logger, arkExecutablePath,
                "switch-branch", "-project", projectName, "-branch", branchName);

        } else {
            // Incremental update - workspace already exists
            logger.message("ARK: Incremental update - reusing existing workspace");

            // Always switch to the correct project and branch in case it changed
            logger.message("ARK: Ensuring correct project " + projectName + " branch " + branchName);
            runArkCommand(checkoutDirectory, logger, arkExecutablePath,
                "switch-branch", "-project", projectName, "-branch", branchName);
        }

        // Get specific changelist
        logger.message("ARK: Getting CL " + toVersion);
        runArkCommand(checkoutDirectory, logger, arkExecutablePath,
            "get", "-cl", toVersion);
        verifyCheckoutPopulated(checkoutDirectory, logger, arkExecutablePath, toVersion);

        logger.message("ARK: Checkout completed successfully");
    }

    /**
     * Check if ark command is available
     */
    private boolean isArkCommandAvailable(String arkExecutablePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(arkExecutablePath, "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Consume output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just consume the output
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveArkExecutablePath(VcsRoot root) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        String osSpecificPath;
        if (osName.contains("win")) {
            osSpecificPath = root.getProperty("arkExecutablePathWindows");
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            osSpecificPath = root.getProperty("arkExecutablePathMac");
        } else {
            osSpecificPath = root.getProperty("arkExecutablePathLinux");
        }

        if (osSpecificPath != null && !osSpecificPath.trim().isEmpty()) {
            return osSpecificPath.trim();
        }

        return "ark";
    }

    /**
     * Run an ark command
     */
    private void runArkCommand(File workingDir, BuildProgressLogger logger,
                              String arkExecutable,
                              String... args) throws VcsException {
        try {
            // Build command
            List<String> command = new ArrayList<>();
            command.add(arkExecutable);
            command.addAll(Arrays.asList(args));

            logger.message("ARK: Running: " + formatCommandForLog(command));
            if (workingDir != null) {
                logger.message("ARK: Working directory: " + workingDir.getAbsolutePath());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output in a separate thread to prevent blocking
            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.message("  " + line);
                        output.append(line).append("\n");
                    }
                } catch (Exception e) {
                    logger.warning("Error reading process output: " + e.getMessage());
                }
            });
            outputReader.start();

            // init-bot receives the token as an argument, so ARK commands should not wait on stdin.
            closeProcessInput(process, logger);

            int exitCode = process.waitFor();

            // Wait for output after the process exits so short-lived Windows commands
            // cannot fail the command just because stdin closed first.
            outputReader.join();

            if (exitCode != 0) {
                throw new VcsException("ARK command failed with exit code " + exitCode +
                    "\nOutput: " + output.toString());
            }

        } catch (Exception e) {
            if (e instanceof VcsException) {
                throw (VcsException) e;
            }
            throw new VcsException("Failed to run ARK command", e);
        }
    }

    private void closeProcessInput(Process process, BuildProgressLogger logger) {
        try {
            process.getOutputStream().close();
        } catch (Exception e) {
            logger.warning("Ignoring failure while closing ARK command stdin: " + e.getMessage());
        }
    }

    private String formatCommandForLog(List<String> command) {
        List<String> masked = new ArrayList<>(command);
        for (int i = 0; i < masked.size() - 1; i++) {
            if ("-token".equals(masked.get(i))) {
                masked.set(i + 1, "********");
            }
        }
        return String.join(" ", masked);
    }

    private String normalizeHostWithDefaultPort(String host) {
        String trimmed = host.trim();
        return trimmed.contains(":") ? trimmed : trimmed + ":9000";
    }

    private void verifyCheckoutPopulated(File checkoutDirectory, BuildProgressLogger logger,
                                         String arkExecutablePath, String toVersion) throws VcsException {
        for (int attempt = 0; attempt <= CHECKOUT_VERIFY_RETRIES; attempt++) {
            CheckoutDirectoryStats stats = collectCheckoutDirectoryStats(checkoutDirectory);
            logger.message("ARK: Checkout contains " + stats.visibleFiles + " files and " +
                stats.visibleDirectories + " directories outside .ark");

            if (stats.visibleDirectories > 0 || stats.visibleFiles == 0) {
                return;
            }

            if (attempt == CHECKOUT_VERIFY_RETRIES) {
                logger.warning("ARK: Checkout has files but no directories outside .ark after verification. " +
                    "Continuing because ARK get completed successfully.");
                return;
            }

            logger.message("ARK: No directories visible after get; retrying CL " + toVersion +
                " after a short delay");
            sleepBeforeCheckoutRetry();
            runArkCommand(checkoutDirectory, logger, arkExecutablePath, "get", "-cl", toVersion);
        }
    }

    private CheckoutDirectoryStats collectCheckoutDirectoryStats(File checkoutDirectory) {
        CheckoutDirectoryStats stats = new CheckoutDirectoryStats();
        collectCheckoutDirectoryStats(checkoutDirectory, stats);
        return stats;
    }

    private void collectCheckoutDirectoryStats(File directory, CheckoutDirectoryStats stats) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.getName().equals(".ark")) {
                continue;
            }

            if (file.isDirectory()) {
                stats.visibleDirectories++;
                collectCheckoutDirectoryStats(file, stats);
            } else if (file.isFile()) {
                stats.visibleFiles++;
            }
        }
    }

    private void sleepBeforeCheckoutRetry() throws VcsException {
        try {
            Thread.sleep(CHECKOUT_VERIFY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VcsException("Interrupted while waiting to verify ARK checkout", e);
        }
    }

    private static class CheckoutDirectoryStats {
        int visibleFiles;
        int visibleDirectories;
    }

    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(File directory, BuildProgressLogger logger) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file, logger);
                    } else {
                        if (!file.delete()) {
                            logger.warning("Failed to delete file: " + file.getAbsolutePath());
                        }
                    }
                }
            }
            if (!directory.delete()) {
                logger.warning("Failed to delete directory: " + directory.getAbsolutePath());
            }
        }
    }
}
