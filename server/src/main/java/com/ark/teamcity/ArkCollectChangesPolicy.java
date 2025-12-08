package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
        // Extract version strings from repository states
        String fromVersion = fromState.getBranchRevisions().values().iterator().next();
        String toVersion = toState.getBranchRevisions().values().iterator().next();

        // Parse changelist numbers
        int fromCl = ArkChangelistParser.extractChangelistNumber(fromVersion);
        int toCl = ArkChangelistParser.extractChangelistNumber(toVersion);

        if (fromCl >= toCl) {
            return Collections.emptyList(); // No new changelists
        }

        LOG.info("Collecting ARK changes from CL " + fromCl + " to CL " + toCl);

        // Get executor for this root
        ArkCommandExecutor executor = myVcs.createExecutorForRoot(toRoot);

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

        LOG.info("Found " + modifications.size() + " changelists");
        return modifications;
    }

    @NotNull
    @Override
    public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                                   @NotNull RepositoryStateData fromState,
                                                   @NotNull RepositoryStateData toState,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Single repository case
        return collectChanges(root, fromState, root, toState, checkoutRules);
    }

    @NotNull
    @Override
    public RepositoryStateData getCurrentState(@NotNull VcsRoot root) throws VcsException {
        String currentVersion = myVcs.getCurrentVersion(root);
        return RepositoryStateData.createSingleVersionState(currentVersion);
    }

    @NotNull
    public RepositoryStateData fetchAllRefs(@NotNull VcsRoot root,
                                             @NotNull CheckoutRules checkoutRules) throws VcsException {
        // For ARK, we only track single branch
        return getCurrentState(root);
    }
}
