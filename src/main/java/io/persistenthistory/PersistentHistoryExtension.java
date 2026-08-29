package io.persistenthistory;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import java.nio.file.Path;

public final class PersistentHistoryExtension implements BurpExtension {
    private HistoryDatabase db;
    private HistoryPanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Persistent HTTP History");
        try {
            Path dbPath = Path.of(System.getProperty("user.home"), ".burp-persistent-history", "history.sqlite3");
            db = new HistoryDatabase(dbPath);
            panel = new HistoryPanel(api, db);
            PersistentHttpHandler handler = new PersistentHttpHandler(db, panel::onCaptured);
            panel.bindHandler(handler);

            api.userInterface().registerSuiteTab("Persistent History", panel);
            api.http().registerHttpHandler(handler);
            api.extension().registerUnloadingHandler(() -> {
                try {
                    if (panel != null) panel.close();
                } catch (Exception ignored) {}
                try {
                    if (db != null) db.close();
                } catch (Exception ignored) {}
            });

            api.logging().logToOutput("Persistent HTTP History v1.0.2 loaded");
            api.logging().logToOutput("Database: " + db.path());
        } catch (Exception e) {
            api.logging().logToError("Persistent HTTP History failed to initialize: " + e);
        }
    }
}
