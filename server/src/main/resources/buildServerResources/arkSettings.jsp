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
                    The ARK server host and port (e.g., ganymede:9000)
                </span>
                <span class="error" id="error_serverHost"></span>
            </td>
        </tr>

        <tr>
            <th><label for="userEmail">User Email:<span class="mandatoryAsterix" title="Mandatory field">*</span></label></th>
            <td>
                <props:textProperty name="userEmail" className="longField" maxlength="256"/>
                <span class="smallNote">
                    Email address for ARK authentication
                </span>
                <span class="error" id="error_userEmail"></span>
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
            <th><label for="branchName">Branch Name:<span class="mandatoryAsterix" title="Mandatory field">*</span></label></th>
            <td>
                <props:textProperty name="branchName" className="longField" maxlength="256"/>
                <span class="smallNote">
                    The branch to monitor for changes (default: main)
                    <br/>You can list branches with <code>ark branch-list -project &lt;name&gt;</code>
                </span>
                <span class="error" id="error_branchName"></span>
            </td>
        </tr>

        <tr>
            <th><label for="arkExecutablePath">ARK Executable:</label></th>
            <td>
                <props:textProperty name="arkExecutablePath" className="longField" maxlength="512"/>
                <span class="smallNote">
                    Path to the ARK executable (default: ark)
                    <br/>Leave as 'ark' if it's in your system PATH
                </span>
                <span class="error" id="error_arkExecutablePath"></span>
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
        <li>The ARK CLI (<code>ark</code>) must be installed on the TeamCity server (for change detection)</li>
        <li><strong>For agent-side checkout:</strong> ARK workspace must be initialized on build agents</li>
        <li>Personal builds are not supported</li>
    </ul>
</div>
