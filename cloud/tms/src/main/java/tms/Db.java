package tms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Connections to the PostgreSQL database: a small pool (idle connections are reused, a broken one
 * is replaced) and the creation of the schema at startup.
 */
final class Db {

    /** Work done with one connection. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final String url;
    private final Properties props = new Properties();
    private final BlockingQueue<Connection> idle;

    Db(String url, String user, String password, String sslMode, int poolSize) {
        this.url = url;
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("sslmode", sslMode);
        props.setProperty("ApplicationName", "htf-tms");
        props.setProperty("connectTimeout", "10");
        this.idle = new ArrayBlockingQueue<>(poolSize);
    }

    /** Runs the work with a pooled connection (autocommit on unless the work changes it). */
    <T> T with(Work<T> work) throws SQLException {
        Connection c = borrow();
        boolean ok = false;
        try {
            T result = work.run(c);
            ok = true;
            return result;
        } finally {
            giveBack(c, ok);
        }
    }

    /** Runs the work in one transaction: committed if it returns, rolled back if it throws. */
    <T> T inTransaction(Work<T> work) throws SQLException {
        return with(c -> {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    /**
     * Waits until the database answers (it may still be booting: docker compose, RDS), then
     * creates the tables if they do not exist (schema.sql).
     */
    void init(int waitSeconds) throws IOException {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (true) {
            try {
                String schema = schema();
                with(c -> {
                    try (Statement s = c.createStatement()) {
                        s.execute(schema);
                    }
                    return null;
                });
                return;
            } catch (SQLException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("could not reach the database at " + url + ": " + e.getMessage(), e);
                }
                System.out.printf("[TMS] waiting for the database at %s (%s)%n", url, e.getMessage());
                sleep(5000);
            }
        }
    }

    private static String schema() throws IOException {
        try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IOException("schema.sql not found in the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Connection borrow() throws SQLException {
        Connection c;
        while ((c = idle.poll()) != null) {
            if (c.isValid(2)) {
                return c;
            }
            close(c);
        }
        return DriverManager.getConnection(url, props);
    }

    private void giveBack(Connection c, boolean ok) {
        try {
            if (!ok && !c.isValid(2)) {
                close(c);
                return;
            }
            if (!c.isClosed() && c.getAutoCommit() && idle.offer(c)) {
                return;
            }
        } catch (SQLException e) {
            // broken connection: drop it
        }
        close(c);
    }

    private static void close(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // already gone
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
