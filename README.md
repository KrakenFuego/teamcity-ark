# teamcity-ark

TeamCity VCS plugin for [ARK](https://ark-vcs.com) version control system.

## Summary

This is a TeamCity VCS plugin for ARK version control. It provides first-class VCS functionality in TeamCity for ARK, with change tracking, change details within TeamCity, patches, and both server-side and agent-side checkout support.

The plugin wraps the ARK CLI client (`ark`) so you will need that configured on your server and build agents.

Currently tested on Linux Teamcity Server & Windows Build Agents.

## Current Status

**Working Features:**
- **Change Detection**: Automatically detects new changelists and triggers builds
- **File-Level Changes**: Shows actual changed files with status (added/modified/deleted)
- **Server-Side Checkout**: Build patches on TeamCity server (requires working directory)
- **Agent-Side Checkout**: Full support for `ark` commands on agents
- **Branch Monitoring**: Track specific branches for changes
- **Build Triggering**: New changelists automatically trigger configured builds
- **Version Display**: Shows changelist numbers in TeamCity UI
- **Labeling/Tagging**: Create tags on builds via `ark tag-add`

**Known Limitations:**
- **Single Branch Only**: Each VCS root monitors one branch at a time
- **N+1 Query**: Each changelist requires a separate `ark print -cl` call (no bulk log command available)

## Requirements

### Server
- TeamCity 2023.11 or later
- Java 8 or later
- ARK CLI (`ark`) installed for change detection

### Build Agents (for Agent-Side Checkout)
- ARK CLI (`ark`) installed and available in PATH
- **Note:** Required only if using agent-side checkout mode

## Installation

1. Download the plugin ZIP from the releases page or build from source
2. Upload to TeamCity: **Administration -> Plugins -> Upload plugin zip**
3. Restart TeamCity server
4. **Install ARK CLI on TeamCity server** (for change detection, required for both checkout modes)
5. **Configure a bot user token in ARK admin UI**
6. **Configure your VCS Root** (select ARK from the list, set your project name, server host, bot token, and working directory)
7. **For Agent-Side Checkout**: Install ARK CLI on all build agents

## Building from Source

```bash
mvn clean package
```

The plugin ZIP will be created at: `build/target/teamcity-ark-vcs.zip`

## Configuration

### VCS Root Settings

- **Server Host**: ARK server host and optional port (e.g., `ganymede:9000`; defaults to port `9000` when omitted)
- **Token**: Bot token shown in the ARK admin UI
- **User Email**: Optional metadata; bot-token authentication does not use this value
- **Project Name**: The ARK project to monitor
- **Branch Name**: The branch to monitor (default: `main`)
- **ARK Executable Windows**: Path to `ark.exe` on Windows machines (e.g., `C:\Apps\Ark_1_0_5\ark.exe`)
- **ARK Executable Mac**: Path to `ark` on macOS machines (e.g., `/usr/local/bin/ark`)
- **ARK Executable Linux**: Path to `ark` on Linux machines (e.g., `/usr/local/bin/ark`)
- **Working Directory**: Path to your ARK workspace on your server

### Finding Your Project Name

```bash
ark project-list
```

### Listing Branches

```bash
ark branch-list -project <project-name>
```

## How It Works

### Change Detection
1. TeamCity polls the ARK repository for new changelists
2. Plugin uses `ark print -cl latest` to get the latest changelist ID
3. Plugin uses `ark print -cl <id>` to get changelist details and file changes
4. If new changelists are detected, TeamCity triggers configured builds showing actual changed files

Change detection always runs on the TeamCity server. Even with agent-side checkout enabled, the TeamCity server must have a native ARK CLI for its own OS and a server-local working directory.

### Checkout Methods

Both server-side and agent-side checkout are supported. Choose the mode that best fits your TeamCity configuration:

**Server-Side Checkout**:
- TeamCity server builds patches and sends them to agents
- Requires **Working Directory** to be configured in VCS root settings
- Server uses `ark get -cl <id>` to retrieve file contents for patch building
- Agents receive patches and don't need ARK CLI installed
- Best for environments where installing CLI on agents is impractical

**Agent-Side Checkout**:
- Agents perform checkout directly using ARK CLI
- Agent runs `ark init-bot -token <token> -host <host:port>` on first checkout
- Subsequent checkouts use `ark get -cl <id>` for fast updates
- Requires ARK CLI installed on all agents
- Uses the ARK executable path for the agent's OS, while server polling uses the TeamCity server's OS path
- Best for distributed teams where agents have full CLI access

## ARK CLI Commands Used

| Operation | Command |
|-----------|---------|
| Get HEAD changelist | `ark print -cl latest` |
| Get changelist details | `ark print -cl <id>` |
| Checkout/get changelist | `ark get -cl <id>` |
| Initialize workspace | `ark init-bot -token <token> -host <host:port>` |
| Create tag | `ark tag-add -name <name> -cl <id>` |
| List projects | `ark project-list` |
| List branches | `ark branch-list -project <name>` |

## Project Structure

```
teamcity-ark/
├── server/              Server-side plugin (change detection, patch building)
├── agent/               Agent-side plugin (workspace checkout)
├── build/               Plugin packaging
├── teamcity-plugin.xml  Plugin descriptor
└── pom.xml             Maven configuration
```

## Known Limitations

- **Single branch monitoring**: Each VCS root can only monitor one branch at a time
- **No bulk history command**: Must fetch each changelist individually
- **Date format**: ARK timestamps don't include year (MM/DD HH:MM format)

## Development

### Local Development Setup

1. Build the plugin: `mvn clean package`
2. Copy to TeamCity plugins directory:
   ```bash
   copy build\target\teamcity-ark-vcs.zip D:\TeamCityData\plugins\
   ```
3. Restart TeamCity server
4. Check logs: `D:\TeamCity\logs\teamcity-server.log`

## Contributing

Contributions are welcome! Please ensure all changes include:
- Updated documentation
- Tested functionality
- Clean commit messages

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For ARK-specific questions, visit [ark-vcs.com](https://ark-vcs.com).

For TeamCity plugin issues, please open a GitHub issue.
