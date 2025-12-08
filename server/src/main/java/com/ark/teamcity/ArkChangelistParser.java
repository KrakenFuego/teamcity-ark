package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.ModificationData;
import jetbrains.buildServer.vcs.VcsChange;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ARK changelist output into TeamCity ModificationData objects
 *
 * Expected format from 'ark print -cl':
 * <pre>
 * CL 3:
 * State: Committed
 * Committed By: cashworth
 * Commit Time: 12/03 16:38
 * Comment: Some commit message
 * Changes: 7
 * * modified_file.txt
 * - deleted_file.txt
 * + added_file.txt
 * </pre>
 */
public class ArkChangelistParser {

    private static final Logger LOG = Logger.getInstance(ArkChangelistParser.class.getName());

    // Pattern: CL 3:
    private static final Pattern CL_PATTERN = Pattern.compile("^CL (\\d+):$");

    // Pattern: State: Committed
    private static final Pattern STATE_PATTERN = Pattern.compile("^State:\\s+(.+)$");

    // Pattern: Committed By: cashworth
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("^Committed By:\\s+(.+)$");

    // Pattern: Commit Time: 12/03 16:38
    private static final Pattern TIME_PATTERN = Pattern.compile("^Commit Time:\\s+(\\d{1,2}/\\d{1,2})\\s+(\\d{1,2}:\\d{2})$");

    // Pattern: Comment: message here
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^Comment:\\s*(.*)$");

    // Pattern: Changes: 7
    private static final Pattern CHANGES_COUNT_PATTERN = Pattern.compile("^Changes:\\s+(\\d+)$");

    // Pattern: * filename (modified), - filename (deleted), + filename (added)
    private static final Pattern FILE_CHANGE_PATTERN = Pattern.compile("^([*+-])\\s+(.+)$");

    // Date format: MM/dd HH:mm (no year in ARK output)
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd HH:mm");

    private final ArkCommandExecutor executor;

    /**
     * Constructor
     * @param executor Command executor for running additional ark commands if needed
     */
    public ArkChangelistParser(@Nullable ArkCommandExecutor executor) {
        this.executor = executor;
    }

    /**
     * Parse a single changelist output into ModificationData
     *
     * @param printOutput Output from 'ark print -cl <id>' command
     * @param root VCS root for the modification
     * @return ModificationData representing the changelist
     * @throws VcsException if parsing fails
     */
    @NotNull
    public ModificationData parseSingleChangelist(@NotNull String printOutput, @NotNull VcsRoot root) throws VcsException {
        String[] lines = printOutput.split("\\r?\\n");

        String changelistId = null;
        String state = null;
        String author = null;
        Date commitTime = null;
        String comment = "";
        List<VcsChange> changes = new ArrayList<>();
        boolean inFileChanges = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines and connection status lines
            if (trimmed.isEmpty() || trimmed.startsWith("Connecting") || trimmed.startsWith("Connected")
                    || trimmed.startsWith("Finished") || trimmed.startsWith("Reconciled")
                    || trimmed.equals("----")) {
                continue;
            }

            // Check for CL line
            Matcher clMatcher = CL_PATTERN.matcher(trimmed);
            if (clMatcher.matches()) {
                changelistId = clMatcher.group(1);
                continue;
            }

            // Check for State line
            Matcher stateMatcher = STATE_PATTERN.matcher(trimmed);
            if (stateMatcher.matches()) {
                state = stateMatcher.group(1).trim();
                continue;
            }

            // Check for Author line
            Matcher authorMatcher = AUTHOR_PATTERN.matcher(trimmed);
            if (authorMatcher.matches()) {
                author = authorMatcher.group(1).trim();
                continue;
            }

            // Check for Time line
            Matcher timeMatcher = TIME_PATTERN.matcher(trimmed);
            if (timeMatcher.matches()) {
                String dateStr = timeMatcher.group(1);
                String timeStr = timeMatcher.group(2);
                commitTime = parseDateTime(dateStr, timeStr);
                continue;
            }

            // Check for Comment line
            Matcher commentMatcher = COMMENT_PATTERN.matcher(trimmed);
            if (commentMatcher.matches()) {
                comment = commentMatcher.group(1).trim();
                continue;
            }

            // Check for Changes count line
            Matcher changesCountMatcher = CHANGES_COUNT_PATTERN.matcher(trimmed);
            if (changesCountMatcher.matches()) {
                inFileChanges = true;
                continue;
            }

            // Check for file change lines
            if (inFileChanges) {
                Matcher fileChangeMatcher = FILE_CHANGE_PATTERN.matcher(trimmed);
                if (fileChangeMatcher.matches()) {
                    String symbol = fileChangeMatcher.group(1);
                    String filePath = fileChangeMatcher.group(2).trim();

                    VcsChange.Type changeType = mapSymbolToChangeType(symbol);
                    if (changeType != null) {
                        // Calculate parent changelist for before/after revisions
                        String parentCl = null;
                        if (changelistId != null) {
                            int clNum = Integer.parseInt(changelistId);
                            if (clNum > 1) {
                                parentCl = String.valueOf(clNum - 1);
                            }
                        }

                        // For ADDED files, there is no before revision
                        // For DELETED files, there is no after revision
                        // For MODIFIED files, both revisions exist
                        String beforeRev = (changeType == VcsChange.Type.ADDED) ? null : parentCl;
                        String afterRev = (changeType == VcsChange.Type.REMOVED) ? null : changelistId;

                        changes.add(new VcsChange(
                                changeType,
                                changeType.name().toLowerCase() + ": " + filePath,
                                filePath,
                                filePath,
                                beforeRev,
                                afterRev
                        ));
                    }
                }
            }
        }

        // Validate required fields
        if (changelistId == null) {
            throw new VcsException("Could not parse changelist ID from output: " + printOutput);
        }

        // Use defaults for missing fields
        if (author == null) {
            author = "unknown";
        }
        if (commitTime == null) {
            commitTime = new Date();
        }
        if (comment.isEmpty()) {
            comment = "No commit message";
        }

        // If no file changes were found, add a generic change entry
        if (changes.isEmpty()) {
            String parentCl = null;
            int clNum = Integer.parseInt(changelistId);
            if (clNum > 1) {
                parentCl = String.valueOf(clNum - 1);
            }

            changes.add(new VcsChange(
                    VcsChange.Type.CHANGED,
                    "Changes detected (file list unavailable)",
                    "(changes detected)",
                    "(changes detected)",
                    parentCl,
                    changelistId
            ));
        }

        return new ModificationData(
                commitTime,
                changes,
                comment,
                author,
                root,
                changelistId,
                changelistId
        );
    }

    /**
     * Parse multiple changelists by fetching each one
     *
     * @param fromCl Starting changelist ID (exclusive)
     * @param toCl Ending changelist ID (inclusive)
     * @param root VCS root
     * @return List of ModificationData for each changelist in range
     */
    @NotNull
    public List<ModificationData> parseChangelistRange(int fromCl, int toCl, @NotNull VcsRoot root) throws VcsException {
        List<ModificationData> modifications = new ArrayList<>();

        if (executor == null) {
            throw new VcsException("Cannot fetch changelist range: executor is null");
        }

        // Fetch each changelist from fromCl+1 to toCl
        for (int cl = fromCl + 1; cl <= toCl; cl++) {
            try {
                String output = executor.getChangelistInfo(String.valueOf(cl));
                ModificationData mod = parseSingleChangelist(output, root);
                modifications.add(mod);
            } catch (VcsException e) {
                LOG.warn("Failed to fetch changelist " + cl + ": " + e.getMessage());
                // Continue with other changelists
            }
        }

        return modifications;
    }

    /**
     * Map ARK file change symbol to TeamCity VcsChange.Type
     *
     * @param symbol The symbol from ARK output (*, -, +)
     * @return The corresponding VcsChange.Type
     */
    @Nullable
    private VcsChange.Type mapSymbolToChangeType(@NotNull String symbol) {
        switch (symbol) {
            case "*":
                return VcsChange.Type.CHANGED;
            case "-":
                return VcsChange.Type.REMOVED;
            case "+":
                return VcsChange.Type.ADDED;
            default:
                LOG.warn("Unknown file change symbol: " + symbol);
                return null;
        }
    }

    /**
     * Parse date and time from ARK format (MM/dd HH:mm)
     * Since ARK doesn't include year, we assume current year or previous year
     * if the date is in the future
     *
     * @param dateStr Date string in MM/dd format
     * @param timeStr Time string in HH:mm format
     * @return Parsed Date object
     */
    @NotNull
    private Date parseDateTime(@NotNull String dateStr, @NotNull String timeStr) {
        try {
            String fullDateStr = dateStr + " " + timeStr;
            Date parsed = DATE_FORMAT.parse(fullDateStr);

            // Set year to current year
            Calendar parsedCal = Calendar.getInstance();
            parsedCal.setTime(parsed);

            Calendar nowCal = Calendar.getInstance();
            parsedCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR));

            // If the resulting date is in the future, it's probably from last year
            if (parsedCal.after(nowCal)) {
                parsedCal.add(Calendar.YEAR, -1);
            }

            return parsedCal.getTime();
        } catch (ParseException e) {
            LOG.warn("Failed to parse date: " + dateStr + " " + timeStr + ", using current time");
            return new Date();
        }
    }

    /**
     * Extract changelist number from a version string
     * ARK uses simple numeric IDs, so this just parses the integer
     *
     * @param version The version string (e.g., "3")
     * @return The numeric changelist ID
     */
    public static int extractChangelistNumber(@NotNull String version) throws VcsException {
        try {
            return Integer.parseInt(version.trim());
        } catch (NumberFormatException e) {
            throw new VcsException("Invalid changelist ID format: " + version);
        }
    }
}
