package com.agentic.orchestrator.events;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed event store (schema in {@code db/migration/V1__run_events.sql}). The primary key
 * on (run_id, seq) means two writers can never both append event N; the loser gets an error.
 */
public final class JdbcEventStore implements EventStore {
    private final DataSource dataSource;

    public JdbcEventStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized Event append(String runId, EventType type, String nodeId, Map<String, String> data,
                                     Instant timestamp) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long seq = 1;
                String prev = Event.GENESIS;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT seq, hash FROM run_events WHERE run_id = ? ORDER BY seq DESC LIMIT 1")) {
                    ps.setString(1, runId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            seq = rs.getLong(1) + 1;
                            prev = rs.getString(2);
                        }
                    }
                }
                Event e = Event.chain(runId, seq, timestamp, type, nodeId, data, prev);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO run_events (run_id, seq, ts, type, node_id, data, prev_hash, hash) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, e.runId());
                    ps.setLong(2, e.seq());
                    ps.setTimestamp(3, Timestamp.from(e.timestamp()));
                    ps.setString(4, e.type().name());
                    ps.setString(5, e.nodeId());
                    ps.setString(6, EventCodec.encodeData(e.data()));
                    ps.setString(7, e.prevHash());
                    ps.setString(8, e.hash());
                    ps.executeUpdate();
                }
                c.commit();
                return e;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to append event for run " + runId, ex);
        }
    }

    @Override
    public List<Event> events(String runId) {
        List<Event> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT run_id, seq, ts, type, node_id, data, prev_hash, hash FROM run_events "
                             + "WHERE run_id = ? ORDER BY seq")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                            EventType.valueOf(rs.getString(4)), rs.getString(5),
                            EventCodec.decodeData(rs.getString(6)), rs.getString(7), rs.getString(8)));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to read events for run " + runId, ex);
        }
    }

    @Override
    public List<String> runIds() {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT run_id FROM run_events GROUP BY run_id ORDER BY MIN(ts)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to list runs", ex);
        }
    }
}
