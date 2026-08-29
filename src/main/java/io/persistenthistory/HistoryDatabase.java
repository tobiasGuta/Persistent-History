package io.persistenthistory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class HistoryDatabase implements AutoCloseable {
    private final Path path;
    private final Connection connection;

    public HistoryDatabase(Path path) throws Exception {
        this.path = path.toAbsolutePath();
        Path parent = this.path.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Burp loads extensions in an isolated class loader. Instantiate the
        // Xerial driver directly instead of relying on DriverManager discovery.
        String jdbcUrl = "jdbc:sqlite:" + this.path;
        org.sqlite.JDBC sqliteDriver = new org.sqlite.JDBC();
        connection = sqliteDriver.connect(jdbcUrl, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite JDBC driver rejected URL: " + jdbcUrl);
        }

        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("""
                CREATE TABLE IF NOT EXISTS http_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    captured_at INTEGER NOT NULL,
                    tool TEXT NOT NULL,
                    method TEXT NOT NULL,
                    url TEXT NOT NULL,
                    status INTEGER NOT NULL,
                    request BLOB NOT NULL,
                    response BLOB
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_http_history_time ON http_history(captured_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_http_history_status ON http_history(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_http_history_method ON http_history(method)");
        }
    }

    public synchronized long insert(long capturedAt, String tool, String method, String url,
                                    int status, byte[] request, byte[] response) throws SQLException {
        String sql = "INSERT INTO http_history(captured_at,tool,method,url,status,request,response) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, capturedAt);
            ps.setString(2, tool);
            ps.setString(3, method);
            ps.setString(4, url);
            ps.setInt(5, status);
            ps.setBytes(6, request);
            if (response == null) ps.setNull(7, Types.BLOB); else ps.setBytes(7, response);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /**
     * Returns table metadata only. Request/response BLOBs are deliberately not
     * materialized here, so loading thousands of rows does not pull large bodies
     * into the Swing table model.
     */
    public synchronized List<HistoryEntry> searchMetadata(String query, int limit) throws SQLException {
        String q = query == null ? "" : query.trim();
        boolean filtered = !q.isEmpty();
        String columns = "id,captured_at,tool,method,url,status";
        String sql = filtered
                ? "SELECT " + columns + " FROM http_history WHERE lower(url) LIKE ? OR lower(method) LIKE ? OR cast(status as text) LIKE ? OR lower(tool) LIKE ? ORDER BY id DESC LIMIT ?"
                : "SELECT " + columns + " FROM http_history ORDER BY id DESC LIMIT ?";

        List<HistoryEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (filtered) {
                String like = "%" + q.toLowerCase() + "%";
                ps.setString(1, like);
                ps.setString(2, like);
                ps.setString(3, like);
                ps.setString(4, like);
                ps.setInt(5, limit);
            } else {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryEntry(
                            rs.getLong("id"),
                            rs.getLong("captured_at"),
                            rs.getString("tool"),
                            rs.getString("method"),
                            rs.getString("url"),
                            rs.getInt("status"),
                            null,
                            null));
                }
            }
        }
        return out;
    }

    public synchronized HistoryEntry get(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM http_history WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? fromRow(rs) : null;
            }
        }
    }

    public synchronized long count() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM http_history")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public synchronized void clear() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM http_history");
        }
    }

    public Path path() {
        return path;
    }

    private static HistoryEntry fromRow(ResultSet rs) throws SQLException {
        return new HistoryEntry(
                rs.getLong("id"),
                rs.getLong("captured_at"),
                rs.getString("tool"),
                rs.getString("method"),
                rs.getString("url"),
                rs.getInt("status"),
                rs.getBytes("request"),
                rs.getBytes("response"));
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
