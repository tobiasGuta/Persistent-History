package io.persistenthistory;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import java.nio.file.Path;

public final class PersistentHistoryExtension implements BurpExtension {
    private WorkspaceManager workspaces;
    private HistoryPanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Persistent HTTP History");
        try {
            Path root = Path.of(System.getProperty("user.home"), ".burp-persistent-history");
            workspaces = new WorkspaceManager(root);
            panel = new HistoryPanel(api, workspaces);
            PersistentHttpHandler handler = new PersistentHttpHandler(workspaces, panel::onCaptured);
            panel.bindHandler(handler);

            api.userInterface().registerSuiteTab("Persistent History", panel);
            api.http().registerHttpHandler(handler);
            api.extension().registerUnloadingHandler(() -> {
                try {
                    if (panel != null) panel.close();
                } catch (Exception ignored) {}
                try {
                    if (workspaces != null) workspaces.close();
                } catch (Exception ignored) {}
            });

            api.logging().logToOutput("Persistent HTTP History v2.1.0 loaded");
            api.logging().logToOutput("Storage root: " + workspaces.root());
            api.logging().logToOutput("Active workspace: " + workspaces.activeWorkspace().name());
        } catch (Exception e) {
            api.logging().logToError("Persistent HTTP History failed to initialize: " + e);
        }
    }
}
