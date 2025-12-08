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
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent-side VCS support for ARK
 * Handles checkout directly on the build agent using ark CLI
 */
public class ArkAgentVcsSupport extends AgentVcsSupport implements UpdateByCheckoutRules2 {

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
        if (!isArkCommandAvailable()) {
            return AgentCheckoutAbility.noVcsClientOnAgent(
                "ARK (ark) command not found on agent. " +
                "Please install ARK CLI and ensure it's in PATH.");
        }

        build.getBuildLogger().message("ARK: Agent checkout is available");
        return AgentCheckoutAbility.canCheckout();
    }

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
        String serverHost = root.getProperty("serverHost");
        String userEmail = root.getProperty("userEmail");
        String userPassword = root.getProperty("secure:userPassword");
        String arkExecutablePath = root.getProperty("arkExecutablePath");
        if (arkExecutablePath == null || arkExecutablePath.trim().isEmpty()) {
            arkExecutablePath = "ark";
        }

        if (projectName == null || projectName.trim().isEmpty()) {
            throw new VcsException("Project name not configured in VCS root");
        }
        if (serverHost == null || serverHost.trim().isEmpty()) {
            throw new VcsException("Server host not configured in VCS root");
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new VcsException("User email not configured in VCS root");
        }
        if (userPassword == null || userPassword.trim().isEmpty()) {
            throw new VcsException("Password not configured in VCS root");
        }

        logger.message("ARK: Project: " + projectName);
        logger.message("ARK: Branch: " + branchName);
        logger.message("ARK: Server: " + serverHost);
        logger.message("ARK: Target CL: " + toVersion);
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
            runArkCommand(checkoutDirectory, logger, arkExecutablePath, userPassword,
                "init", "-email", userEmail, "-host", serverHost);

            // Switch to the correct project and branch
            logger.message("ARK: Switching to project " + projectName + " branch " + branchName);
            runArkCommand(checkoutDirectory, logger, arkExecutablePath, userPassword,
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
            runArkCommand(checkoutDirectory, logger, arkExecutablePath, userPassword,
                "init", "-email", userEmail, "-host", serverHost);

            // Switch to the correct project and branch
            logger.message("ARK: Switching to project " + projectName + " branch " + branchName);
            runArkCommand(checkoutDirectory, logger, arkExecutablePath, userPassword,
                "switch-branch", "-project", projectName, "-branch", branchName);

        } else {
            // Incremental update - workspace already exists
            logger.message("ARK: Incremental update - reusing existing workspace");

            // Always switch to the correct project and branch in case it changed
            logger.message("ARK: Ensuring correct project " + projectName + " branch " + branchName);
            runArkCommand(checkoutDirectory, logger, arkExecutablePath, userPassword,
                "switch-branch", "-project", projectName, "-branch", branchName);
        }

        // Get specific changelist
        logger.message("ARK: Getting CL " + toVersion);
        runArkCommand(checkoutDirectory, logger, arkExecutablePath, userPassword,
            "get", "-cl", toVersion);

        logger.message("ARK: Checkout completed successfully");
    }

    /**
     * Check if ark command is available
     */
    private boolean isArkCommandAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ark", "--help");
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

    /**
     * Run an ark command
     */
    private void runArkCommand(File workingDir, BuildProgressLogger logger,
                              String arkExecutable, String password,
                              String... args) throws VcsException {
        try {
            // Build command
            List<String> command = new ArrayList<>();
            command.add(arkExecutable);
            command.addAll(Arrays.asList(args));

            logger.message("ARK: Running: " + String.join(" ", command));
            if (workingDir != null) {
                logger.message("ARK: Working directory: " + workingDir.getAbsolutePath());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Check if this is a command that needs authentication (init command)
            boolean needsAuth = args.length > 0 && "init".equals(args[0]);

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

            // Only send password for commands that need authentication
            if (needsAuth && password != null && !password.isEmpty()) {
                logger.message("ARK: Waiting for password prompt...");
                // Give the process time to start and prompt for password
                Thread.sleep(5000);

                // Only send password if process is still running (waiting for input)
                if (process.isAlive()) {
                    OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream());
                    writer.write(password);
                    writer.write("\n");
                    writer.flush();
                    writer.close();
                    logger.message("ARK: Password sent");
                } else {
                    logger.message("ARK: Process completed without needing password (cached auth)");
                }
            } else {
                process.getOutputStream().close();
            }

            // Wait for output reader to finish
            outputReader.join();

            int exitCode = process.waitFor();
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
