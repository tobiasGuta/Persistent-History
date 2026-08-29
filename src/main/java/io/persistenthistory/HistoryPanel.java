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
    private final HistoryDatabase db;
    private final HistoryTableModel model = new HistoryTableModel();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField();
    private final JLabel status = new JLabel();
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final ExecutorService dbExecutor;
    private final Timer searchTimer;
    private final Timer captureRefreshTimer;
    private final AtomicBoolean reloadRunning = new AtomicBoolean(false);
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final AtomicLong selectionGeneration = new AtomicLong(0);
    private volatile boolean closed;
    private PersistentHttpHandler handler;

    public HistoryPanel(MontoyaApi api, HistoryDatabase db) {
        super(new BorderLayout());
        this.api = api;
        this.db = db;
        this.requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "persistent-history-db");
            t.setDaemon(true);
            return t;
        });

        JButton refresh = new JButton("Refresh");
        JToggleButton capture = new JToggleButton("Capture enabled", true);
        JButton clear = new JButton("Clear history");

        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        controls.add(new JLabel("Search:"), BorderLayout.WEST);
        controls.add(search, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(status);
        buttons.add(refresh);
        buttons.add(capture);
        buttons.add(clear);
        controls.add(buttons, BorderLayout.EAST);
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

    public void onCaptured(HistoryEntry ignored) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !captureRefreshTimer.isRunning()) {
                captureRefreshTimer.start();
            }
        });
    }

    private void reloadAsync() {
        if (closed) return;
        if (!reloadRunning.compareAndSet(false, true)) {
            reloadPending.set(true);
            return;
        }

        final String query = search.getText();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new LoadResult(db.searchMetadata(query, UI_ROW_LIMIT), db.count());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dbExecutor)
                .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (closed) return;
                        if (error != null) {
                            status.setText("Database error");
                            api.logging().logToError("Persistent History reload failed: " + error.getCause());
                        } else {
                            model.setRows(result.rows());
                            status.setText("Stored: " + result.count() + (result.count() > UI_ROW_LIMIT ? " (showing newest " + UI_ROW_LIMIT + ")" : ""));
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
        long generation = selectionGeneration.incrementAndGet();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return db.get(id);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dbExecutor)
                .whenComplete((entry, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed || generation != selectionGeneration.get()) return;
                    if (error != null) {
                        api.logging().logToError("Persistent History entry load failed: " + error.getCause());
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
        if (JOptionPane.showConfirmDialog(
                this,
                "Delete all persisted HTTP history?",
                "Clear history",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }

        CompletableFuture
                .runAsync(() -> {
                    try {
                        db.clear();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dbExecutor)
                .whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    if (error != null) {
                        api.logging().logToError("Clear failed: " + error.getCause());
                        return;
                    }
                    selectionGeneration.incrementAndGet();
                    requestEditor.setRequest(HttpRequest.httpRequest());
                    responseEditor.setResponse(HttpResponse.httpResponse());
                    reloadAsync();
                }));
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

    private record LoadResult(List<HistoryEntry> rows, long count) {}
}
