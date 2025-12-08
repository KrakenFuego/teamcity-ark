package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.patches.PatchBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * Main VCS support implementation for ARK version control system.
 *
 * <p>This plugin supports both server-side and agent-side checkout modes:
 * <ul>
 *   <li><b>Server-side checkout</b>: Implements {@link BuildPatchByCheckoutRules} to build patches on the TeamCity server.
 *       Requires WORKING_DIRECTORY configuration but agents don't need ARK CLI.</li>
 *   <li><b>Agent-side checkout</b>: Uses {@link com.ark.teamcity.agent.ArkAgentVcsSupport} on agents.
 *       Requires ARK CLI installed and authenticated on all build agents.</li>
 * </ul>
 *
 * @see ArkCommandExecutor for CLI command execution
 * @see ArkCollectChangesPolicy for change detection logic
 */
public class ArkVcsSupport extends ServerVcsSupport implements BuildPatchByCheckoutRules {

    private static final Logger LOG = Logger.getInstance(ArkVcsSupport.class.getName());

    public ArkVcsSupport() {
        // Default constructor
    }

    @NotNull
    @Override
    public String getName() {
        return "ark";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "ARK";
    }

    @NotNull
    @Override
    public String getVcsSettingsJspFilePath() {
        return "arkSettings.jsp";
    }

    @NotNull
    @Override
    public String getCurrentVersion(@NotNull VcsRoot root) throws VcsException {
        LOG.info("Getting current version for ARK project: " + root.getProperty(ArkSettings.PROJECT_NAME));
        ArkCommandExecutor executor = createExecutor(root);
        String version = executor.getCurrentChangelistId();
        LOG.info("Current version: CL " + version);
        return version;
    }

    @NotNull
    public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                                   @NotNull String fromVersion,
                                                   @NotNull String currentVersion,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Delegate to the collect changes policy to avoid code duplication
        RepositoryStateData fromState = RepositoryStateData.createSingleVersionState(fromVersion);
        RepositoryStateData toState = RepositoryStateData.createSingleVersionState(currentVersion);
        return getCollectChangesPolicy().collectChanges(root, fromState, toState, checkoutRules);
    }

    /**
     * Builds a patch for server-side checkout.
     *
     * This method enables server-side checkout by building patches that TeamCity can send to agents.
     * The implementation uses the ARK CLI to get specific changelists and read file contents.
     *
     * <p><b>Requirements:</b>
     * <ul>
     *   <li>WORKING_DIRECTORY must be configured in VCS root settings</li>
     *   <li>ARK CLI must be installed and authenticated on TeamCity server</li>
     * </ul>
     *
     * <p><b>How it works:</b>
     * <ul>
     *   <li>Full checkout (fromVersion=null): Gets toVersion and adds all files to patch</li>
     *   <li>Incremental checkout: Collects changes between versions, gets each changelist, and builds patch with modified files</li>
     * </ul>
     *
     * <p><b>Note:</b> Build agents do NOT need ARK CLI when using server-side checkout.
     *
     * @param root The VCS root configuration
     * @param fromVersion The previous version (null for full checkout)
     * @param toVersion The target version to checkout
     * @param builder The TeamCity patch builder
     * @param checkoutRules Checkout rules to apply
     * @throws VcsException If checkout fails or working directory is not configured
     */
    public void buildPatch(@NotNull VcsRoot root,
                           @Nullable String fromVersion,
                           @NotNull String toVersion,
                           @NotNull PatchBuilder builder,
                           @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Get the list of changes between versions
        List<ModificationData> changes;
        if (fromVersion == null) {
            // Full checkout - get all files at toVersion
            changes = Collections.emptyList();
            buildFullPatch(root, toVersion, builder, checkoutRules);
        } else {
            // Incremental patch - get changes between fromVersion and toVersion
            changes = collectChanges(root, fromVersion, toVersion, checkoutRules);
            buildIncrementalPatch(root, changes, builder, checkoutRules);
        }
    }

    private void buildFullPatch(@NotNull VcsRoot root,
                                 @NotNull String toVersion,
                                 @NotNull PatchBuilder builder,
                                 @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Get the changelist in the workspace and add all files
        ArkCommandExecutor executor = createExecutor(root);
        executor.getChangelist(toVersion);

        String workingDir = root.getProperty(ArkSettings.WORKING_DIRECTORY);
        if (workingDir == null || workingDir.trim().isEmpty()) {
            throw new VcsException("Working directory is not configured");
        }

        File workDir = new File(workingDir);
        addDirectoryToPatch(workDir, "", builder, checkoutRules);
    }

    private void addDirectoryToPatch(@NotNull File dir,
                                      @NotNull String relativePath,
                                      @NotNull PatchBuilder builder,
                                      @NotNull CheckoutRules checkoutRules) throws VcsException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            // Skip .ark directory
            if (file.getName().equals(".ark")) continue;

            String filePath = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();

            if (file.isDirectory()) {
                addDirectoryToPatch(file, filePath, builder, checkoutRules);
            } else {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    // Create a relative File object for the patch (not the absolute file)
                    File relativeFile = new File(filePath);
                    builder.createBinaryFile(relativeFile, filePath, fis, file.length());
                } catch (Exception e) {
                    throw new VcsException("Failed to add file to patch: " + filePath, e);
                }
            }
        }
    }

    private void buildIncrementalPatch(@NotNull VcsRoot root,
                                         @NotNull List<ModificationData> changes,
                                         @NotNull PatchBuilder builder,
                                         @NotNull CheckoutRules checkoutRules) throws VcsException {
        ArkCommandExecutor executor = createExecutor(root);

        // Process each modification
        for (ModificationData mod : changes) {
            for (VcsChange change : mod.getChanges()) {
                String filePath = change.getRelativeFileName();

                switch (change.getType()) {
                    case ADDED:
                        // Get toVersion and get file content
                        executor.getChangelist(mod.getVersion());
                        String workingDir = root.getProperty(ArkSettings.WORKING_DIRECTORY);
                        File addedFile = new File(workingDir, filePath);
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(addedFile)) {
                            // Use relative File object for the patch
                            File relativeAddedFile = new File(filePath);
                            builder.createBinaryFile(relativeAddedFile, filePath, fis, addedFile.length());
                        } catch (Exception e) {
                            throw new VcsException("Failed to add file: " + filePath, e);
                        }
                        break;

                    case REMOVED:
                        try {
                            // Use relative File object for the patch
                            File relativeRemovedFile = new File(filePath);
                            builder.deleteFile(relativeRemovedFile, true);
                        } catch (Exception e) {
                            throw new VcsException("Failed to delete file: " + filePath, e);
                        }
                        break;

                    case CHANGED:
                        // Get toVersion and get file content
                        executor.getChangelist(mod.getVersion());
                        String workDir = root.getProperty(ArkSettings.WORKING_DIRECTORY);
                        File changedFile = new File(workDir, filePath);
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(changedFile)) {
                            // Use relative File object for the patch
                            File relativeChangedFile = new File(filePath);
                            builder.changeOrCreateBinaryFile(relativeChangedFile, filePath, fis, changedFile.length());
                        } catch (Exception e) {
                            throw new VcsException("Failed to change file: " + filePath, e);
                        }
                        break;
                }
            }
        }
    }

    @Nullable
    @Override
    public BuildPatchPolicy getBuildPatchPolicy() {
        // Return this to enable server-side checkout via patch building
        return this;
    }

    @NotNull
    @Override
    public ArkCollectChangesPolicy getCollectChangesPolicy() {
        // Return separate policy instance
        return new ArkCollectChangesPolicy(this);
    }

    @Override
    public boolean isAgentSideCheckoutAvailable() {
        // Agent-side checkout is supported
        return true;
    }

    @Nullable
    @Override
    public VcsFileContentProvider getContentProvider() {
        // Return provider that gets changelists and reads files from workspace
        return new ArkFileContentProvider(this);
    }

    @Nullable
    @Override
    public LabelingSupport getLabelingSupport() {
        return new LabelingSupport() {
            @NotNull
            @Override
            public String label(@NotNull String label, @NotNull String version, @NotNull VcsRoot root, @NotNull CheckoutRules checkoutRules) throws VcsException {
                // Sanitize label name (replace spaces and special chars with underscores)
                String sanitizedLabel = label.trim().replace(' ', '_').replace('\t', '_').replace('\n', '_');

                ArkCommandExecutor executor = createExecutor(root);
                String projectName = root.getProperty(ArkSettings.PROJECT_NAME);
                executor.createTag(sanitizedLabel, version, projectName);

                return sanitizedLabel;
            }
        };
    }

    @Override
    public boolean sourcesUpdatePossibleIfChangesNotFound(@NotNull VcsRoot root) {
        // Return true to allow updates even when changes are not found
        return true;
    }

    @NotNull
    @Override
    public String describeVcsRoot(@NotNull VcsRoot root) {
        String projectName = root.getProperty(ArkSettings.PROJECT_NAME);
        String branch = root.getProperty(ArkSettings.BRANCH_NAME, ArkSettings.DEFAULT_BRANCH_NAME);
        String host = root.getProperty(ArkSettings.SERVER_HOST);
        return "ARK project: " + projectName + " (branch: " + branch + ") @ " + host;
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultVcsProperties() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put(ArkSettings.BRANCH_NAME, ArkSettings.DEFAULT_BRANCH_NAME);
        defaults.put(ArkSettings.ARK_EXECUTABLE_PATH, ArkSettings.DEFAULT_ARK_EXECUTABLE);
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getVcsPropertiesProcessor() {
        return new AbstractVcsPropertiesProcessor() {
            @Override
            public Collection<InvalidProperty> process(Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<>();

                // Validate project name
                String projectName = properties.get(ArkSettings.PROJECT_NAME);
                if (projectName == null || projectName.trim().isEmpty()) {
                    errors.add(new InvalidProperty(ArkSettings.PROJECT_NAME,
                            "Project name is required"));
                }

                // Validate server host
                String serverHost = properties.get(ArkSettings.SERVER_HOST);
                if (serverHost == null || serverHost.trim().isEmpty()) {
                    errors.add(new InvalidProperty(ArkSettings.SERVER_HOST,
                            "Server host is required"));
                }

                // Validate user email
                String userEmail = properties.get(ArkSettings.USER_EMAIL);
                if (userEmail == null || userEmail.trim().isEmpty()) {
                    errors.add(new InvalidProperty(ArkSettings.USER_EMAIL,
                            "User email is required"));
                }

                // Validate branch name
                String branch = properties.get(ArkSettings.BRANCH_NAME);
                if (branch == null || branch.trim().isEmpty()) {
                    errors.add(new InvalidProperty(ArkSettings.BRANCH_NAME,
                            "Branch name is required"));
                }

                // Validate executable path
                String arkPath = properties.get(ArkSettings.ARK_EXECUTABLE_PATH);
                if (arkPath == null || arkPath.trim().isEmpty()) {
                    errors.add(new InvalidProperty(ArkSettings.ARK_EXECUTABLE_PATH,
                            "ARK executable path is required"));
                }

                return errors;
            }
        };
    }

    @NotNull
    public String getVersionDisplayName(@NotNull String version, @NotNull VcsRoot root) throws VcsException {
        // Display changelist number
        int clNum = ArkChangelistParser.extractChangelistNumber(version);
        return "CL " + clNum;
    }

    @NotNull
    public Comparator<String> getVersionComparator() {
        return new Comparator<String>() {
            @Override
            public int compare(String v1, String v2) {
                try {
                    int num1 = ArkChangelistParser.extractChangelistNumber(v1);
                    int num2 = ArkChangelistParser.extractChangelistNumber(v2);
                    return Integer.compare(num1, num2);
                } catch (VcsException e) {
                    return v1.compareTo(v2); // Fallback to string comparison
                }
            }
        };
    }

    @Nullable
    public String testConnection(@NotNull VcsRoot root) throws VcsException {
        try {
            ArkCommandExecutor executor = createExecutor(root);
            executor.testConnection();
            return null; // Success - return null
        } catch (VcsException e) {
            return "Connection test failed: " + e.getMessage();
        }
    }

    // Helper method to create command executor
    @NotNull
    private ArkCommandExecutor createExecutor(@NotNull VcsRoot root) {
        String arkPath = root.getProperty(ArkSettings.ARK_EXECUTABLE_PATH,
                ArkSettings.DEFAULT_ARK_EXECUTABLE);
        String workingDir = root.getProperty(ArkSettings.WORKING_DIRECTORY);

        File workDir = null;
        if (workingDir != null && !workingDir.trim().isEmpty()) {
            workDir = new File(workingDir);
        }

        return new ArkCommandExecutor(arkPath, workDir);
    }

    // Public helper method for ArkCollectChangesPolicy
    @NotNull
    public ArkCommandExecutor createExecutorForRoot(@NotNull VcsRoot root) {
        return createExecutor(root);
    }
}
