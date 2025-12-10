package com.ark.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Handles branch specification pattern matching for ARK VCS.
 *
 * <p>Patterns (one per line):
 * <ul>
 *   <li>{@code +:<pattern>} or {@code +<pattern>} : Include branches matching pattern</li>
 *   <li>{@code -:<pattern>} or {@code -<pattern>} : Exclude branches matching pattern</li>
 *   <li>{@code <pattern>} : Include (+ is default)</li>
 * </ul>
 *
 * <p>Wildcards:
 * <ul>
 *   <li>{@code *} : Matches any characters (zero or more)</li>
 *   <li>{@code ?} : Matches single character</li>
 * </ul>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code +:*} : Include all branches</li>
 *   <li>{@code +:feature/*} : Include feature branches</li>
 *   <li>{@code -:wip/*} : Exclude wip branches</li>
 * </ul>
 *
 * <p>Note: ARK branches are simple names (main, dev, feature/foo).
 * The refs/heads/ prefix is stripped if users copy Git patterns.
 */
public class ArkBranchSpec {

    private static final Logger LOG = Logger.getInstance(ArkBranchSpec.class.getName());
    private static final String REFS_HEADS_PREFIX = "refs/heads/";

    private final List<BranchRule> rules;

    /**
     * Create a branch spec from a specification string.
     *
     * @param spec The branch specification (lines separated by newlines), or null/empty for no filtering
     */
    public ArkBranchSpec(@Nullable String spec) {
        this.rules = new ArrayList<>();

        if (spec == null || spec.trim().isEmpty()) {
            return;
        }

        for (String line : spec.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // Skip empty lines and comments
            }

            BranchRule rule = parseRule(trimmed);
            if (rule != null) {
                rules.add(rule);
            }
        }
    }

    /**
     * Check if a branch name matches this specification.
     *
     * @param branchName The branch name to check (can be with or without refs/heads/ prefix)
     * @return true if the branch matches (included and not excluded), false otherwise
     */
    public boolean matches(@NotNull String branchName) {
        // Normalize: strip refs/heads/ prefix if present
        String normalizedBranch = branchName;
        if (normalizedBranch.startsWith(REFS_HEADS_PREFIX)) {
            normalizedBranch = normalizedBranch.substring(REFS_HEADS_PREFIX.length());
        }

        // If no rules, nothing matches (user must explicitly include branches)
        if (rules.isEmpty()) {
            LOG.debug("No rules defined, branch '" + normalizedBranch + "' does not match");
            return false;
        }

        // Apply rules in order - last matching rule wins
        boolean matched = false;
        for (BranchRule rule : rules) {
            boolean ruleMatches = rule.pattern.matcher(normalizedBranch).matches();
            if (ruleMatches) {
                matched = rule.include;
                LOG.debug("Branch '" + normalizedBranch + "' " +
                    (rule.include ? "included" : "excluded") +
                    " by pattern: " + rule.pattern.pattern());
            }
        }

        LOG.debug("Branch '" + normalizedBranch + "' final match result: " + matched);
        return matched;
    }

    /**
     * Check if this spec has any rules defined.
     *
     * @return true if rules are defined, false if empty
     */
    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /**
     * Parse a single rule line.
     */
    @Nullable
    private BranchRule parseRule(String line) {
        boolean include = true;
        String pattern = line;

        // Check for +: or -: prefix
        if (pattern.startsWith("+:")) {
            include = true;
            pattern = pattern.substring(2);
        } else if (pattern.startsWith("-:")) {
            include = false;
            pattern = pattern.substring(2);
        } else if (pattern.startsWith("+")) {
            include = true;
            pattern = pattern.substring(1);
        } else if (pattern.startsWith("-")) {
            include = false;
            pattern = pattern.substring(1);
        }

        // Strip refs/heads/ prefix if user copied a Git pattern
        if (pattern.startsWith(REFS_HEADS_PREFIX)) {
            pattern = pattern.substring(REFS_HEADS_PREFIX.length());
        }

        pattern = pattern.trim();
        if (pattern.isEmpty()) {
            return null;
        }

        // Convert glob pattern to regex
        String regex = globToRegex(pattern);

        try {
            BranchRule rule = new BranchRule(include, Pattern.compile(regex));
            LOG.info("Parsed branch rule: " + (include ? "+" : "-") + ":" + pattern + " -> " + regex);
            return rule;
        } catch (Exception e) {
            LOG.warn("Failed to parse branch pattern '" + pattern + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Convert a glob pattern to a regex pattern.
     *
     * <p>For branch matching, we use simplified semantics:
     * <ul>
     *   <li>{@code *} matches any characters (including /)</li>
     *   <li>{@code ?} matches any single character</li>
     * </ul>
     *
     * <p>This differs from file path globs where * doesn't cross directory boundaries.
     * For branch specs like {@code +:*}, we want to match all branches including
     * those with slashes like {@code feature/foo}.
     *
     * @param glob The glob pattern (supports * and ?)
     * @return The equivalent regex pattern
     */
    @NotNull
    private String globToRegex(@NotNull String glob) {
        StringBuilder regex = new StringBuilder();
        regex.append("^");

        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    // * matches anything (including path separators) for branch names
                    // Skip consecutive *s
                    while (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                    }
                    regex.append(".*");
                    break;
                case '?':
                    // ? matches any single character
                    regex.append(".");
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '|':
                case '^':
                case '$':
                case '+':
                case '\\':
                    // Escape regex special characters
                    regex.append("\\");
                    regex.append(c);
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }

        regex.append("$");
        LOG.debug("Converted glob '" + glob + "' to regex: " + regex.toString());
        return regex.toString();
    }

    /**
     * A single branch matching rule.
     */
    private static class BranchRule {
        final boolean include;
        final Pattern pattern;

        BranchRule(boolean include, Pattern pattern) {
            this.include = include;
            this.pattern = pattern;
        }
    }
}
