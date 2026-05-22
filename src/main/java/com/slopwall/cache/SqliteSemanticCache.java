package com.slopwall.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Optional;

public final class SqliteSemanticCache implements SemanticCache, AutoCloseable {

    private final Connection conn;
    private final EmbeddingEngine engine;
    private final float threshold;

    public SqliteSemanticCache(String jdbcPath, EmbeddingEngine engine, float threshold)
            throws SQLException {
        this.engine = engine;
        this.threshold = threshold;
        conn = DriverManager.getConnection("jdbc:sqlite:" + jdbcPath);
        try (var st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS semantic_cache (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant       TEXT    NOT NULL,
                    prompt_hash  TEXT    NOT NULL,
                    prompt_text  TEXT    NOT NULL,
                    embedding    BLOB    NOT NULL,
                    response     TEXT    NOT NULL,
                    created_at   INTEGER NOT NULL
                )""");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_tenant_hash ON semantic_cache(tenant, prompt_hash)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tenant ON semantic_cache(tenant)");
        }
    }

    @Override
    public Optional<String> lookup(String tenantId, String prompt) {
        String hash = sha256(prompt);
        // 1. Exact hash shortcut (O(1))
        try (var ps = conn.prepareStatement(
                "SELECT response FROM semantic_cache WHERE tenant=? AND prompt_hash=?")) {
            ps.setString(1, tenantId);
            ps.setString(2, hash);
            var rs = ps.executeQuery();
            if (rs.next()) return Optional.of(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // 2. Cosine scan for tenant
        float[] queryVec = engine.embed(prompt);
        try (var ps = conn.prepareStatement(
                "SELECT embedding, response FROM semantic_cache WHERE tenant=? ORDER BY created_at DESC LIMIT 1000")) {
            ps.setString(1, tenantId);
            var rs = ps.executeQuery();
            float bestSim = -1f;
            String bestResp = null;
            while (rs.next()) {
                float[] storedVec = bytesToFloats(rs.getBytes(1));
                float sim = CosineDistance.similarity(queryVec, storedVec);
                if (sim > bestSim) { bestSim = sim; bestResp = rs.getString(2); }
            }
            if (bestSim >= threshold) return Optional.of(bestResp);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void store(String tenantId, String prompt, String response) {
        String hash = sha256(prompt);
        float[] vec = engine.embed(prompt);
        byte[] blob = floatsToBytes(vec);
        try (var ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO semantic_cache(tenant,prompt_hash,prompt_text,embedding,response,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, hash);
            ps.setString(3, prompt);
            ps.setBytes(4, blob);
            ps.setString(5, response);
            ps.setLong(6, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        conn.close();
    }

    private static byte[] floatsToBytes(float[] v) {
        var bb = ByteBuffer.allocate(4 * v.length).order(ByteOrder.BIG_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return bb.array();
    }

    private static float[] bytesToFloats(byte[] b) {
        var bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }

    private static String sha256(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
