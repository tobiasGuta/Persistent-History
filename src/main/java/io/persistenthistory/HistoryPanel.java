package io.persistenthistory;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class HistoryPanel extends JPanel implements AutoCloseable {
    private static final int UI_ROW_LIMIT = 5000;

    private final MontoyaApi api;
    private final WorkspaceManager workspaces;
    private final HistoryTableModel model = new HistoryTableModel();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField();
    private final JLabel status = new JLabel();
    private final JLabel scopeLabel = new JLabel();
    private final JComboBox<Workspace> workspaceCombo = new JComboBox<>();
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final ExecutorService dbExecutor;
    private final Timer searchTimer;
    private final Timer captureRefreshTimer;
    private final AtomicBoolean reloadRunning = new AtomicBoolean(false);
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final AtomicLong selectionGeneration = new AtomicLong(0);
    private final AtomicLong workspaceGeneration = new AtomicLong(0);
    private volatile boolean closed;
    private boolean updatingWorkspaceCombo;
    private PersistentHttpHandler handler;

    public HistoryPanel(MontoyaApi api, WorkspaceManager workspaces) {
        super(new BorderLayout());
        this.api = api;
        this.workspaces = workspaces;
        this.requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "persistent-history-db");
            t.setDaemon(true);
            return t;
        });

        JButton newWorkspace = new JButton("New workspace");
        JButton editScope = new JButton("Scope...");
        JButton refresh = new JButton("Refresh");
        JToggleButton capture = new JToggleButton("Capture enabled", true);
        JButton clear = new JButton("Clear workspace");

        JPanel workspaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        workspaceRow.add(new JLabel("Workspace:"));
        workspaceCombo.setPrototypeDisplayValue(
                new Workspace("prototype", "Example Program Workspace", List.of(), workspaces.root(), false));
        workspaceRow.add(workspaceCombo);
        workspaceRow.add(newWorkspace);
        workspaceRow.add(editScope);
        workspaceRow.add(scopeLabel);

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.add(new JLabel("Search:"), BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(status);
        buttons.add(refresh);
        buttons.add(capture);
        buttons.add(clear);
        searchRow.add(buttons, BorderLayout.EAST);

        JPanel controls = new JPanel(new BorderLayout(0, 5));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        controls.add(workspaceRow, BorderLayout.NORTH);
        controls.add(searchRow, BorderLayout.CENTER);
        add(controls, BorderLayout.NORTH);

        JPanel requestPanel = editorPanel("Original request", requestEditor.uiComponent());
        JPanel responsePanel = editorPanel("Response", responseEditor.uiComponent());
        JSplitPane editors = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, requestPanel, responsePanel);
        editors.setResizeWeight(0.5);
        editors.setContinuousLayout(true);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), editors);
        split.setResizeWeight(0.42);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                int row = table.convertRowIndexToModel(table.getSelectedRow());
                loadEntryAsync(model.get(row).id());
            }
        });

        searchTimer = new Timer(250, e -> reloadAsync());
        searchTimer.setRepeats(false);
        captureRefreshTimer = new Timer(250, e -> reloadAsync());
        captureRefreshTimer.setRepeats(false);

        refreshWorkspaceChoices(workspaces.activeWorkspace().id());
        updateScopeLabel();

        workspaceCombo.addActionListener(e -> switchWorkspaceFromUi());
        newWorkspace.addActionListener(e -> createWorkspace());
        editScope.addActionListener(e -> editWorkspaceScope());
        refresh.addActionListener(e -> reloadAsync());
        capture.addActionListener(e -> {
            if (handler != null) handler.setEnabled(capture.isSelected());
            capture.setText(capture.isSelected() ? "Capture enabled" : "Capture paused");
        });
        clear.addActionListener(e -> clearHistory());

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { searchTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { searchTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { searchTimer.restart(); }
        });

        api.userInterface().applyThemeToComponent(this);
        reloadAsync();
    }

    private static JPanel editorPanel(String title, Component editor) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(title);
        label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        panel.add(label, BorderLayout.NORTH);
        panel.add(editor, BorderLayout.CENTER);
        return panel;
    }

    public void bindHandler(PersistentHttpHandler handler) {
        this.handler = handler;
    }

    public void onCaptured(WorkspaceManager.CaptureResult result) {
        SwingUtilities.invokeLater(() -> {
            if (closed || result == null) return;
            Workspace current = workspaces.activeWorkspace();
            if (current.id().equals(result.workspaceId()) && !captureRefreshTimer.isRunning()) {
                captureRefreshTimer.start();
            }
        });
    }

    private void switchWorkspaceFromUi() {
        if (closed || updatingWorkspaceCombo) return;
        Workspace selected = (Workspace) workspaceCombo.getSelectedItem();
        if (selected == null) return;
        try {
            workspaces.setActiveWorkspace(selected.id());
            workspaceChanged();
        } catch (Exception e) {
            api.logging().logToError("Workspace switch failed: " + e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not switch workspace: " + e.getMessage(),
                    "Workspace error",
                    JOptionPane.ERROR_MESSAGE);
            refreshWorkspaceChoices(workspaces.activeWorkspace().id());
        }
    }

    private void createWorkspace() {
        JTextField name = new JTextField(30);
        JTextArea targets = new JTextArea(5, 34);
        targets.setLineWrap(true);
        targets.setWrapStyleWord(true);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel("Workspace / program name:"));
        form.add(name);
        form.add(Box.createVerticalStrut(8));
        form.add(new JLabel("Quick target roots (one per line or comma-separated, optional):"));
        form.add(new JScrollPane(targets));
        form.add(new JLabel(
                "<html>Example: example.com also permits api.example.com. "
                        + "Use Scope... after creation for Burp-style include/exclude rules or JSON import.</html>"));

        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "New workspace",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        try {
            Workspace created = workspaces.createWorkspace(
                    name.getText(),
                    WorkspaceManager.parseTargets(targets.getText()));
            refreshWorkspaceChoices(created.id());
            workspaceChanged();
        } catch (Exception e) {
            api.logging().logToError("Workspace creation failed: " + e);
            JOptionPane.showMessageDialog(
                    this,
                    e.getMessage(),
                    "Workspace error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editWorkspaceScope() {
        Workspace workspace = workspaces.activeWorkspace();
        if (workspace.legacy()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Legacy / Unscoped preserves the original v1 database and captures all targets.\n"
                            + "Create a managed workspace to use scope isolation.",
                    "Legacy workspace",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        WorkspaceScope updatedScope = ScopeEditorDialog.showDialog(this, workspace);
        if (updatedScope == null) {
            return;
        }

        try {
            Workspace updated = workspaces.updateScope(workspace.id(), updatedScope);
            refreshWorkspaceChoices(updated.id());
            workspaceChanged();
        } catch (Exception e) {
            api.logging().logToError("Workspace scope update failed: " + e);
            JOptionPane.showMessageDialog(
                    this,
                    e.getMessage(),
                    "Workspace error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void workspaceChanged() {
        workspaceGeneration.incrementAndGet();
        selectionGeneration.incrementAndGet();
        model.setRows(List.of());
        clearEditors();
        updateScopeLabel();
        reloadAsync();
    }

    private void refreshWorkspaceChoices(String selectedId) {
        updatingWorkspaceCombo = true;
        try {
            DefaultComboBoxModel<Workspace> comboModel = new DefaultComboBoxModel<>();
            Workspace selected = null;
            for (Workspace workspace : workspaces.workspaces()) {
                comboModel.addElement(workspace);
                if (workspace.id().equals(selectedId)) {
                    selected = workspace;
                }
            }
            workspaceCombo.setModel(comboModel);
            if (selected != null) {
                workspaceCombo.setSelectedItem(selected);
            }
        } finally {
            updatingWorkspaceCombo = false;
        }
    }

    private void updateScopeLabel() {
        Workspace workspace = workspaces.activeWorkspace();
        String label = workspace.legacy()
                ? "Scope: All (legacy / unscoped)"
                : "Scope: " + workspace.scopeLabel();
        scopeLabel.setText(label);
        scopeLabel.setToolTipText(label);
    }

    private void reloadAsync() {
        if (closed) return;
        if (!reloadRunning.compareAndSet(false, true)) {
            reloadPending.set(true);
            return;
        }

        Workspace workspace = workspaces.activeWorkspace();
        String workspaceId = workspace.id();
        long generation = workspaceGeneration.get();
        HistoryDatabase db = workspaces.databaseFor(workspaceId);
        String query = search.getText();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new LoadResult(
                                workspaceId,
                                workspace,
                                db.searchMetadata(query, UI_ROW_LIMIT),
                                db.count());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dbExecutor)
                .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (closed) return;
                        boolean stale = generation != workspaceGeneration.get()
                                || !workspaceId.equals(workspaces.activeWorkspace().id());
                        if (stale) return;

                        if (error != null) {
                            status.setText("Database error");
                            api.logging().logToError(
                                    "Persistent History reload failed: " + error.getCause());
                        } else {
                            model.setRows(result.rows());
                            long skipped = workspaces.skippedOutsideTargets(result.workspaceId());
                            String limit = result.count() > UI_ROW_LIMIT
                                    ? " (showing newest " + UI_ROW_LIMIT + ")"
                                    : "";
                            String isolation = result.workspace().scoped()
                                    ? " | skipped outside scope: " + skipped
                                    : "";
                            status.setText("Stored: " + result.count() + limit + isolation);
                        }
                    } finally {
                        reloadRunning.set(false);
                        if (reloadPending.getAndSet(false) && !closed) {
                            reloadAsync();
                        }
                    }
                }));
    }

    private void loadEntryAsync(long id) {
        if (closed) return;
        Workspace workspace = workspaces.activeWorkspace();
        String workspaceId = workspace.id();
        HistoryDatabase db = workspaces.databaseFor(workspaceId);
        long selection = selectionGeneration.incrementAndGet();
        long generation = workspaceGeneration.get();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return db.get(id);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dbExecutor)
                .whenComplete((entry, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed
                            || selection != selectionGeneration.get()
                            || generation != workspaceGeneration.get()) {
                        return;
                    }
                    if (!workspaceId.equals(workspaces.activeWorkspace().id())) return;
                    if (error != null) {
                        api.logging().logToError(
                                "Persistent History entry load failed: " + error.getCause());
                        return;
                    }
                    showEntry(entry);
                }));
    }

    private void showEntry(HistoryEntry entry) {
        if (entry == null) return;

        byte[] request = entry.request();
        if (request == null) {
            requestEditor.setRequest(HttpRequest.httpRequest());
        } else {
            ByteArray requestBytes = ByteArray.byteArray(request);
            HttpRequest restored = RequestTarget.fromUrl(entry.url())
                    .map(target -> HttpRequest.httpRequest(
                            HttpService.httpService(target.host(), target.port(), target.secure()),
                            requestBytes))
                    .orElseGet(() -> HttpRequest.httpRequest(requestBytes));
            requestEditor.setRequest(restored);
        }

        byte[] response = entry.response();
        if (response == null) {
            responseEditor.setResponse(HttpResponse.httpResponse());
        } else {
            responseEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(response)));
        }

        requestEditor.setCaretPosition(0);
        responseEditor.setCaretPosition(0);
    }

    private void clearHistory() {
        Workspace workspace = workspaces.activeWorkspace();
        if (JOptionPane.showConfirmDialog(
                this,
                "Delete all persisted HTTP history from workspace '" + workspace.name() + "'?",
                "Clear workspace history",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }

        String workspaceId = workspace.id();
        HistoryDatabase db = workspaces.databaseFor(workspaceId);
        CompletableFuture
                .runAsync(() -> {
                    try {
                        db.clear();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dbExecutor)
                .whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed || !workspaceId.equals(workspaces.activeWorkspace().id())) return;
                    if (error != null) {
                        api.logging().logToError("Clear failed: " + error.getCause());
                        return;
                    }
                    selectionGeneration.incrementAndGet();
                    clearEditors();
                    reloadAsync();
                }));
    }

    private void clearEditors() {
        requestEditor.setRequest(HttpRequest.httpRequest());
        responseEditor.setResponse(HttpResponse.httpResponse());
    }

    @Override
    public void close() {
        closed = true;
        searchTimer.stop();
        captureRefreshTimer.stop();
        dbExecutor.shutdownNow();
        try {
            dbExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record LoadResult(
            String workspaceId,
            Workspace workspace,
            List<HistoryEntry> rows,
            long count) {}
}
