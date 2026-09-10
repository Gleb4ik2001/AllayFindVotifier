package net.allayfind.paper;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public final class Inbox implements AutoCloseable {
    private final Connection db;
    private static final Gson JSON = new Gson();
    public record Reward(String id, String nickname, List<String> commands) {}

    public Inbox(Path file, String scope) throws SQLException {
        db = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        try {
            try (Statement sql = db.createStatement()) {
                sql.execute("PRAGMA journal_mode=WAL");
                sql.execute("PRAGMA synchronous=FULL");
                sql.execute("PRAGMA busy_timeout=5000");
                sql.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                sql.execute("CREATE TABLE IF NOT EXISTS votes (id TEXT PRIMARY KEY, nickname TEXT NOT NULL, commands TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', created TEXT NOT NULL)");
                sql.execute("CREATE INDEX IF NOT EXISTS votes_state ON votes(state)");
            }
            try (PreparedStatement insert = db.prepareStatement("INSERT OR IGNORE INTO metadata VALUES ('scope', ?)")) {
                insert.setString(1, scope); insert.executeUpdate();
            }
            try (Statement sql = db.createStatement(); ResultSet row = sql.executeQuery("SELECT value FROM metadata WHERE key='scope'")) {
                if (!row.next() || !scope.equals(row.getString(1))) throw new SQLException("Database belongs to another site/server. Restore matching config.");
            }
            try (Statement sql = db.createStatement()) {
                sql.executeUpdate("UPDATE votes SET state='uncertain' WHERE state='executing'");
            }
        } catch (SQLException e) { db.close(); throw e; }
    }

    public void save(List<VoteEvent> events, List<String> commands) throws SQLException {
        db.setAutoCommit(false);
        try (PreparedStatement insert = db.prepareStatement("INSERT OR IGNORE INTO votes(id,nickname,commands,created) VALUES (?,?,?,?)")) {
            for (VoteEvent event : events) {
                insert.setString(1, event.id().toString()); insert.setString(2, event.nickname());
                insert.setString(3, JSON.toJson(commands)); insert.setString(4, event.createdAt().toString());
                insert.addBatch();
            }
            insert.executeBatch(); db.commit();
        } catch (SQLException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }

    public List<Reward> pending(String nickname) throws SQLException {
        try (PreparedStatement sql = db.prepareStatement("SELECT id,nickname,commands FROM votes WHERE state='pending' AND nickname=? COLLATE NOCASE ORDER BY created,id LIMIT 10")) {
            sql.setString(1, nickname);
            List<Reward> result = new ArrayList<>();
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) result.add(new Reward(rows.getString(1), rows.getString(2),
                        List.of(JSON.fromJson(rows.getString(3), String[].class))));
            }
            return result;
        }
    }

    public boolean transition(String id, String from, String to) throws SQLException {
        try (PreparedStatement sql = db.prepareStatement("UPDATE votes SET state=? WHERE id=? AND state=?")) {
            sql.setString(1, to); sql.setString(2, id); sql.setString(3, from);
            return sql.executeUpdate() == 1;
        }
    }

    public String status() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("SELECT state,COUNT(*) FROM votes GROUP BY state")) {
            while (rows.next()) result.add(rows.getString(1) + "=" + rows.getInt(2));
        }
        return result.isEmpty() ? "No stored votes" : String.join(", ", result);
    }

    public List<String> unresolved() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("SELECT id,nickname,state FROM votes WHERE state!='done' ORDER BY created LIMIT 20")) {
            while (rows.next()) result.add(rows.getString(1) + " " + rows.getString(2) + " " + rows.getString(3));
        }
        return result;
    }

    public void close() throws SQLException { db.close(); }
}
