package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Collect changes policy for ARK VCS
 * Implements CollectChangesBetweenRepositories to detect changes between versions
 */
public class ArkCollectChangesPolicy implements CollectChangesBetweenRepositories {

    private static final Logger LOG = Logger.getInstance(ArkCollectChangesPolicy.class.getName());

    private final ArkVcsSupport myVcs;

    public ArkCollectChangesPolicy(@NotNull ArkVcsSupport vcs) {
        this.myVcs = vcs;
    }

    @NotNull
    @Override
    public List<ModificationData> collectChanges(@NotNull VcsRoot fromRoot,
                                                   @NotNull RepositoryStateData fromState,
                                                   @NotNull VcsRoot toRoot,
                                                   @NotNull RepositoryStateData toState,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Get the default branch from state - this is the branch TeamCity is asking about
        String defaultBranch = toState.getDefaultBranchName();
        if (defaultBranch == null) {
            // Fallback: use the configured default branch
            String configuredBranch = toRoot.getProperty(ArkSettings.BRANCH_NAME, ArkSettings.DEFAULT_BRANCH_NAME);
            defaultBranch = myVcs.normalizeBranchName(configuredBranch);
        }

        LOG.info("Collecting changes for branch: " + defaultBranch);

        // Extract version strings for the specific branch
        Map<String, String> fromBranches = fromState.getBranchRevisions();
        Map<String, String> toBranches = toState.getBranchRevisions();

        String fromVersion = fromBranches.get(defaultBranch);
        String toVersion = toBranches.get(defaultBranch);

        // If branch not found in from state, it's a new branch - no changes to collect
        if (fromVersion == null) {
            LOG.info("Branch " + defaultBranch + " not found in from state, treating as new branch");
            return Collections.emptyList();
        }

        if (toVersion == null) {
            LOG.warn("Branch " + defaultBranch + " not found in to state");
            return Collections.emptyList();
        }

        // Parse changelist numbers
        int fromCl = ArkChangelistParser.extractChangelistNumber(fromVersion);
        int toCl = ArkChangelistParser.extractChangelistNumber(toVersion);

        if (fromCl >= toCl) {
            return Collections.emptyList(); // No new changelists
        }

        LOG.info("Collecting ARK changes from CL " + fromCl + " to CL " + toCl + " on branch " + defaultBranch);

        // Get executor for this root
        ArkCommandExecutor executor = myVcs.createExecutorForRoot(toRoot);

        // Ensure workspace is initialized before running commands
        String host = toRoot.getProperty(ArkSettings.SERVER_HOST);
        if (host != null) {
            executor.ensureWorkspaceInitialized(host);
        }

        // Switch to the correct branch before collecting changes
        String projectName = toRoot.getProperty(ArkSettings.PROJECT_NAME);
        String arkBranch = myVcs.getArkBranchName(defaultBranch);
        if (projectName != null && arkBranch != null) {
            LOG.info("Switching to branch " + arkBranch + " for change collection");
            executor.switchBranch(projectName, arkBranch);
        }

        // Create parser with executor to fetch individual changelists
        ArkChangelistParser parser = new ArkChangelistParser(executor);

        // Fetch all changelists in range
        List<ModificationData> modifications = parser.parseChangelistRange(fromCl, toCl, toRoot);

        // Sort by changelist number (oldest first)
        modifications.sort(new Comparator<ModificationData>() {
            @Override
            public int compare(ModificationData o1, ModificationData o2) {
                try {
                    int num1 = ArkChangelistParser.extractChangelistNumber(o1.getVersion());
                    int num2 = ArkChangelistParser.extractChangelistNumber(o2.getVersion());
                    return Integer.compare(num1, num2);
                } catch (VcsException e) {
                    return 0;
                }
            }
        });

        LOG.info("Found " + modifications.size() + " changelists on branch " + defaultBranch);
        return modifications;
    }

    @NotNull
    @Override
    public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                                   @NotNull RepositoryStateData fromState,
                                                   @NotNull RepositoryStateData toState,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Check if this is multi-branch mode
        Map<String, String> fromBranches = fromState.getBranchRevisions();
        Map<String, String> toBranches = toState.getBranchRevisions();

        // If we have multiple branches, collect changes for each
        if (toBranches.size() > 1 || fromBranches.size() > 1) {
            return collectMultiBranchChanges(root, fromState, toState, checkoutRules);
        }

        // Single repository/branch case - delegate to the existing method
        return collectChanges(root, fromState, root, toState, checkoutRules);
    }

    /**
     * Collect changes across multiple branches.
     */
    @NotNull
    private List<ModificationData> collectMultiBranchChanges(@NotNull VcsRoot root,
                                                               @NotNull RepositoryStateData fromState,
                                                               @NotNull RepositoryStateData toState,
                                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
        List<ModificationData> allModifications = new ArrayList<>();

        Map<String, String> fromBranches = fromState.getBranchRevisions();
        Map<String, String> toBranches = toState.getBranchRevisions();
        String projectName = root.getProperty(ArkSettings.PROJECT_NAME);

        LOG.info("Collecting multi-branch changes for " + toBranches.size() + " branches");

        // Get executor and ensure workspace is initialized
        ArkCommandExecutor executor = myVcs.createExecutorForRoot(root);
        String host = root.getProperty(ArkSettings.SERVER_HOST);
        if (host != null) {
            executor.ensureWorkspaceInitialized(host);
        }

        for (Map.Entry<String, String> entry : toBranches.entrySet()) {
            String branch = entry.getKey();
            String toVersion = entry.getValue();
            String fromVersion = fromBranches.get(branch);

            // Skip if no from version (new branch) or same version (no changes)
            if (fromVersion == null) {
                LOG.info("Branch " + branch + " is new, skipping change collection");
                continue;
            }
            if (fromVersion.equals(toVersion)) {
                LOG.info("Branch " + branch + " has no changes");
                continue;
            }

            // Parse changelist numbers
            int fromCl, toCl;
            try {
                fromCl = ArkChangelistParser.extractChangelistNumber(fromVersion);
                toCl = ArkChangelistParser.extractChangelistNumber(toVersion);
            } catch (VcsException e) {
                LOG.warn("Could not parse changelist numbers for branch " + branch + ": " + e.getMessage());
                continue;
            }

            if (fromCl >= toCl) {
                LOG.info("Branch " + branch + " has no forward changes (from " + fromCl + " to " + toCl + ")");
                continue;
            }

            LOG.info("Collecting changes for branch " + branch + " from CL " + fromCl + " to CL " + toCl);

            // Switch to this branch
            String arkBranch = myVcs.getArkBranchName(branch);
            executor.switchBranch(projectName, arkBranch);

            // Collect changes for this branch
            ArkChangelistParser parser = new ArkChangelistParser(executor);
			List<ModificationData> mods = parser.parseChangelistRangeForBranch(fromCl, toCl, root, branch);
            allModifications.addAll(mods);

            LOG.info("Found " + mods.size() + " changes on branch " + branch);
        }

        // Sort all modifications by CL number (oldest first)
        allModifications.sort((a, b) -> {
            try {
                int num1 = ArkChangelistParser.extractChangelistNumber(a.getVersion());
                int num2 = ArkChangelistParser.extractChangelistNumber(b.getVersion());
                return Integer.compare(num1, num2);
            } catch (VcsException e) {
                return 0;
            }
        });

        LOG.info("Total changes across all branches: " + allModifications.size());
        return allModifications;
    }

    @NotNull
    @Override
    public RepositoryStateData getCurrentState(@NotNull VcsRoot root) throws VcsException {
        // Delegate to ArkVcsSupport for multi-branch support
        return myVcs.getCurrentState(root);
    }

    @NotNull
    public RepositoryStateData fetchAllRefs(@NotNull VcsRoot root,
                                             @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Delegate to getCurrentState which now supports multi-branch
        return getCurrentState(root);
    }
}
