package com.lauriewired;

import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.services.ProgramManager;
import ghidra.framework.options.Options;
import ghidra.framework.plugintool.Plugin;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.util.Msg;

import java.io.IOException;

/**
 * GUI plugin wrapper: starts a {@link GhidraMCPServer} bound to the tool's current program.
 * All request handling lives in GhidraMCPServer so the same code serves the headless host.
 */
@PluginInfo(
    status = PluginStatus.RELEASED,
    packageName = ghidra.app.DeveloperPluginPackage.NAME,
    category = PluginCategoryNames.ANALYSIS,
    shortDescription = "HTTP server plugin",
    description = "Starts an embedded HTTP server (GhidraMCPServer) to expose program data. Port configurable via Tool Options."
)
public class GhidraMCPPlugin extends Plugin {

    private static final String OPTION_CATEGORY_NAME = "GhidraMCP HTTP Server";
    private static final String PORT_OPTION_NAME = "Server Port";
    private static final int DEFAULT_PORT = 8080;

    private GhidraMCPServer server;

    public GhidraMCPPlugin(PluginTool tool) {
        super(tool);
        Msg.info(this, "GhidraMCPPlugin loading...");

        Options options = tool.getOptions(OPTION_CATEGORY_NAME);
        options.registerOption(PORT_OPTION_NAME, DEFAULT_PORT, null,
            "The base port number the embedded HTTP server will listen on. " +
            "If the port is in use, the server will auto-increment up to 10 ports. " +
            "Requires Ghidra restart or plugin reload to take effect after changing.");
        int port = options.getInt(PORT_OPTION_NAME, DEFAULT_PORT);

        server = new GhidraMCPServer(
            () -> {
                ProgramManager pm = tool.getService(ProgramManager.class);
                return pm != null ? pm.getCurrentProgram() : null;
            },
            port, tool);
        try {
            server.start();
        }
        catch (IOException e) {
            Msg.error(this, "Failed to start HTTP server", e);
        }
        Msg.info(this, "GhidraMCPPlugin loaded!");
    }

    @Override
    public void dispose() {
        if (server != null) {
            server.stop();
            server = null;
        }
        super.dispose();
    }
}
