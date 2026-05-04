<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>

<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>

<style type="text/css">
    .arkSettings {
        margin-bottom: 1em;
    }
    .arkSettings label {
        font-weight: bold;
        display: block;
        margin-bottom: 0.3em;
    }
    .arkSettings .description {
        color: #888;
        font-size: 0.9em;
        margin-top: 0.2em;
    }
</style>

<div class="arkSettings">
    <table class="runnerFormTable">
        <tr>
            <th><label for="serverHost">Server Host:<span class="mandatoryAsterix" title="Mandatory field">*</span></label></th>
            <td>
                <props:textProperty name="serverHost" className="longField" maxlength="256"/>
                <span class="smallNote">
                    The ARK server host and optional port (e.g., ganymede:9000). If omitted, port 9000 is used.
                </span>
                <span class="error" id="error_serverHost"></span>
            </td>
        </tr>

        <tr>
            <th><label for="userEmail">User Email:</label></th>
            <td>
                <props:textProperty name="userEmail" className="longField" maxlength="256"/>
                <span class="smallNote">
                    Optional metadata only. ARK workspace initialization uses the bot token below.
                </span>
                <span class="error" id="error_userEmail"></span>
            </td>
        </tr>

        <tr>
            <th><label for="secure:userPassword">Token:<span class="mandatoryAsterix" title="Mandatory field">*</span></label></th>
            <td>
                <props:passwordProperty name="secure:userPassword" className="longField"/>
                <span class="smallNote">
                    Bot token for ARK authentication. Use the token shown for the bot user in the ARK admin UI.
                </span>
                <span class="error" id="error_secure:userPassword"></span>
            </td>
        </tr>

        <tr>
            <th><label for="projectName">Project Name:<span class="mandatoryAsterix" title="Mandatory field">*</span></label></th>
            <td>
                <props:textProperty name="projectName" className="longField" maxlength="256"/>
                <span class="smallNote">
                    The ARK project name to monitor
                    <br/>You can find this by running <code>ark project-list</code> command
                </span>
                <span class="error" id="error_projectName"></span>
            </td>
        </tr>

        <tr>
            <th><label for="branchName">Default Branch:<span class="mandatoryAsterix" title="Mandatory field">*</span></label></th>
            <td>
                <props:textProperty name="branchName" className="longField" maxlength="256"/>
                <span class="smallNote">
                    The default branch to monitor for changes (e.g., main, dev)
                    <br/>You can list branches with <code>ark branch-list -project &lt;name&gt;</code>
                </span>
                <span class="error" id="error_branchName"></span>
            </td>
        </tr>

        <bs:branchSpecTableRow/>

        <tr>
            <th><label for="arkExecutablePathWindows">ARK Executable Windows:</label></th>
            <td>
                <props:textProperty name="arkExecutablePathWindows" className="longField" maxlength="512"/>
                <span class="smallNote">
                    Path to the ARK executable on Windows machines (e.g., C:\Apps\Ark_1_0_5\ark.exe)
                    <br/>Leave empty to use <code>ark</code> from PATH on Windows.
                </span>
                <span class="error" id="error_arkExecutablePathWindows"></span>
            </td>
        </tr>

        <tr>
            <th><label for="arkExecutablePathMac">ARK Executable Mac:</label></th>
            <td>
                <props:textProperty name="arkExecutablePathMac" className="longField" maxlength="512"/>
                <span class="smallNote">
                    Path to the ARK executable on macOS machines (e.g., /usr/local/bin/ark)
                    <br/>Leave empty to use <code>ark</code> from PATH on macOS.
                </span>
                <span class="error" id="error_arkExecutablePathMac"></span>
            </td>
        </tr>

        <tr>
            <th><label for="arkExecutablePathLinux">ARK Executable Linux:</label></th>
            <td>
                <props:textProperty name="arkExecutablePathLinux" className="longField" maxlength="512"/>
                <span class="smallNote">
                    Path to the ARK executable on Linux machines (e.g., /usr/local/bin/ark)
                    <br/>Leave empty to use <code>ark</code> from PATH on Linux.
                </span>
                <span class="error" id="error_arkExecutablePathLinux"></span>
            </td>
        </tr>

        <tr>
            <th><label for="workingDirectory">Working Directory:</label></th>
            <td>
                <props:textProperty name="workingDirectory" className="longField" maxlength="512"/>
                <span class="smallNote">
                    Working directory for ARK operations
                    <br/><strong>Required for server-side checkout</strong>. Leave empty for agent-side checkout.
                </span>
                <span class="error" id="error_workingDirectory"></span>
            </td>
        </tr>

    </table>
</div>

<div class="arkSettings">
    <h3>Important Notes:</h3>
    <ul>
        <li>Both <strong>server-side</strong> and <strong>agent-side</strong> checkout modes are supported</li>
        <li><strong>Server-side checkout</strong>: Requires <strong>Working Directory</strong> to be configured. Agents don't need ARK CLI.</li>
        <li><strong>Agent-side checkout</strong>: Requires ARK CLI (<code>ark</code>) installed on all build agents</li>
        <li>The ARK CLI (<code>ark</code>) must be installed on the TeamCity server for VCS polling and change detection, even when checkout is agent-side.</li>
        <li>The plugin chooses the ARK executable path for the operating system running each command. Server polling uses the server OS path; agent-side checkout uses the agent OS path.</li>
        <li>Workspace initialization uses <code>ark init-bot -token &lt;token&gt; -host &lt;host:port&gt;</code>; configure the token from the ARK admin UI bot user.</li>
        <li><strong>For agent-side checkout:</strong> ARK workspaces are initialized on build agents with the configured bot token</li>
        <li>Personal builds are not supported</li>
    </ul>
</div>
