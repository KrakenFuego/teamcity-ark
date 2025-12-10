package com.ark.teamcity;

/**
 * Constants for ARK VCS root settings
 */
public class ArkSettings {

    /**
     * Project name in ARK
     */
    public static final String PROJECT_NAME = "projectName";

    /**
     * Branch name to monitor (default: main)
     */
    public static final String BRANCH_NAME = "branchName";

    /**
     * ARK server host (e.g., ganymede:9000)
     */
    public static final String SERVER_HOST = "serverHost";

    /**
     * User email for ARK authentication
     */
    public static final String USER_EMAIL = "userEmail";

    /**
     * User password for ARK authentication (secure property)
     */
    public static final String USER_PASSWORD = "secure:userPassword";

    /**
     * Path to ARK executable (default: ark)
     */
    public static final String ARK_EXECUTABLE_PATH = "arkExecutablePath";

    /**
     * Working directory for ARK operations (optional)
     */
    public static final String WORKING_DIRECTORY = "workingDirectory";

    /**
     * Branch specification for feature branch support (TeamCity standard property)
     */
    public static final String BRANCH_SPEC = "teamcity:branchSpec";

    /**
     * Branch name prefix for TeamCity compatibility (normalizes ARK branch names)
     */
    public static final String BRANCH_NAME_PREFIX = "refs/heads/";

    /**
     * Default branch name
     */
    public static final String DEFAULT_BRANCH_NAME = "main";

    /**
     * Default executable path
     */
    public static final String DEFAULT_ARK_EXECUTABLE = "ark";

    /**
     * Maximum number of retries for get operations
     */
    public static final int MAX_GET_RETRIES = 10;

    /**
     * Delay between get retries in milliseconds
     */
    public static final long GET_RETRY_DELAY_MS = 1000L;

    /**
     * Extra changelists to fetch when retrieving history (buffer to ensure we get all needed CLs)
     */
    public static final int CL_FETCH_BUFFER = 10;
}
