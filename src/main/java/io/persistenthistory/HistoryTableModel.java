package io.persistenthistory;

import javax.swing.table.AbstractTableModel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class HistoryTableModel extends AbstractTableModel {
    private static final String[] COLS = {"ID", "Time", "Tool", "Method", "Status", "URL"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private List<HistoryEntry> rows = new ArrayList<>();

    public void setRows(List<HistoryEntry> rows) { this.rows = new ArrayList<>(rows); fireTableDataChanged(); }
    public HistoryEntry get(int row) { return rows.get(row); }
    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int c) { return COLS[c]; }
    @Override public Object getValueAt(int r, int c) {
        HistoryEntry e = rows.get(r);
        return switch (c) {
            case 0 -> e.id();
            case 1 -> TIME.format(Instant.ofEpochMilli(e.capturedAt()));
            case 2 -> e.tool();
            case 3 -> e.method();
            case 4 -> e.status();
            case 5 -> e.url();
            default -> "";
        };
    }
}
