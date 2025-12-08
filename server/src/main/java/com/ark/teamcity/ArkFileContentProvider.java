package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;

/**
 * Provides file content at specific versions for ARK VCS.
 * Used by TeamCity to show diffs and file contents in the UI.
 */
public class ArkFileContentProvider implements VcsFileContentProvider {

    private static final Logger LOG = Logger.getInstance(ArkFileContentProvider.class.getName());

    private final ArkVcsSupport myVcs;

    public ArkFileContentProvider(@NotNull ArkVcsSupport vcs) {
        this.myVcs = vcs;
    }

    @NotNull
    @Override
    public byte[] getContent(@NotNull VcsModification vcsModification,
                             @NotNull VcsChangeInfo change,
                             @NotNull VcsChangeInfo.ContentType contentType,
                             @NotNull VcsRoot root) throws VcsException {
        String version;
        if (contentType == VcsChangeInfo.ContentType.BEFORE_CHANGE) {
            version = change.getBeforeChangeRevisionNumber();
        } else {
            version = change.getAfterChangeRevisionNumber();
        }

        if (version == null) {
            throw new VcsFileNotFoundException("File not found", change.getRelativeFileName(), null);
        }

        return getContent(change.getRelativeFileName(), root, version);
    }

    @NotNull
    @Override
    public byte[] getContent(@NotNull String filePath, @NotNull VcsRoot root, @NotNull String version) throws VcsException {
        LOG.info("Getting content for " + filePath + " at CL " + version);

        ArkCommandExecutor executor = myVcs.createExecutorForRoot(root);

        // Get the specific changelist
        executor.getChangelist(version);

        // Read file from working directory
        String workingDir = root.getProperty(ArkSettings.WORKING_DIRECTORY);
        if (workingDir == null || workingDir.trim().isEmpty()) {
            throw new VcsException("Working directory is not configured - cannot retrieve file content");
        }

        File file = new File(workingDir, filePath);
        if (!file.exists()) {
            throw new VcsFileNotFoundException("File not found at CL " + version, filePath, null);
        }

        try {
            return Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            throw new VcsException("Failed to read file content: " + filePath, e);
        }
    }
}
