/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.griefcraft.sql;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.zaxxer.hikari.HikariDataSource;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.griefcraft.lwc.LWC;
import com.griefcraft.scripting.ModuleException;
import com.griefcraft.util.Statistics;
import com.griefcraft.util.config.Configuration;

public abstract class Database {

    public enum Type {
        MySQL("com.mysql.jdbc.Driver"), //
        SQLite("org.sqlite.JDBC"), //
        NONE("nil"); //

        private String driver;

        Type(String driver) {
            this.driver = driver;
        }

        public String getDriver() {
            return driver;
        }

        /**
         * Match the given string to a database type
         *
         * @param str
         * @return
         */
        public static Type matchType(String str) {
            for (Type type : values()) {
                if (type.toString().equalsIgnoreCase(str)) {
                    return type;
                }
            }

            return null;
        }

    }

    public interface ThrowingCallback<T, R> {
        T call(R r) throws Throwable;
    }

    public interface ThrowingConsumer<R> {
        void accept(R r) throws Throwable;
    }

    /**
     * The database engine being used for this connection
     */
    public Type currentType;

    /**
     * Store cached prepared statements.
     * <p/>
     * Since SQLite JDBC doesn't cache them.. we do it ourselves :S
     */
    private Cache<String, PreparedStatement> statementCache = CacheBuilder
            .newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .removalListener(notif -> closeQuietly((PreparedStatement) notif.getValue()))
            .build();

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) { }
    }
    
    /**
     * Hikari connection pool
     */
    private final HikariDataSource ds;

    /**
     * The default database engine being used. This is set via config
     *
     * @default SQLite
     */
    public static Type DefaultType = Type.NONE;

    /**
     * If we are connected to sqlite
     */
    private boolean connected = false;

    /**
     * If the database has been loaded
     */
    protected boolean loaded = false;

    /**
     * The database prefix (only if we're using MySQL.)
     */
    protected String prefix = "";

    /**
     * If the high level statement cache should be used. If this is false, already cached statements are ignored
     */
    private boolean useStatementCache = true;

    public Database() {
        this(DefaultType);
    }

    public Database(Type currentType) {
        this.currentType = currentType;

        prefix = LWC.getInstance().getConfiguration().getString("database.prefix", "");
        if (prefix == null) {
            prefix = "";
        }

        ds = new HikariDataSource();
        ds.setDriverClassName(currentType.getDriver());
        ds.setJdbcUrl("jdbc:" + currentType.toString().toLowerCase() + ":" + getDatabasePath());
        ds.setConnectionTimeout(5000);

        // if we're using mysql, set the database login info
        if (currentType == Type.MySQL) {
            LWC lwc = LWC.getInstance();
            ds.setUsername(lwc.getConfiguration().getString("database.username"));
            ds.setPassword(lwc.getConfiguration().getString("database.password"));
        }
    }

    /**
     * Ping the database to keep the connection alive
     */
    public void pingDatabase() {
        try (Connection connection = getConnection()) {
            Statement stmt = connection.createStatement();
            stmt.executeQuery("SELECT 1;");
        } catch (SQLException ex) {
            log("Keepalive packet (ping) failed!");
            ex.printStackTrace();
        }
    }

    /**
     * Set the value of auto commit
     *
     * @param autoCommit
     * @return Whether or not the database was set to auto commit or not
     * @throws IllegalStateException If the configuration is already sealed
     */
    public boolean setAutoCommit(boolean autoCommit) {
        boolean wasAutoCommit = ds.isAutoCommit();
        ds.setAutoCommit(autoCommit);
        return wasAutoCommit;
    }

    /**
     * Set the value of read only
     *
     * @param readOnly Whether or not this database should be read only
     * @return Whether or not the database was readonly or not
     * @throws IllegalStateException If the configuration is already sealed
     */
    public boolean setReadOnly(boolean readOnly) {
        boolean wasReadOnly = ds.isReadOnly();
        ds.setReadOnly(readOnly);
        return wasReadOnly;
    }

    /**
     * @return the table prefix
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Print an exception to stdout
     *
     * @param exception
     */
    protected void printException(Exception exception) {
        throw new ModuleException(exception);
    }

    /**
     * Connect to MySQL
     *
     * @return if the connection was succesful
     */
    public boolean connect() {
        if (currentType == null || currentType == Type.NONE) {
            log("Invalid database engine");
            return false;
        }

        try (Connection connection = getConnection()) {
            Statement stmt = connection.createStatement();
            stmt.executeQuery("SELECT 1;");
            connected = true;
            return true;
        } catch (SQLException e) {
            log("Failed to connect to " + currentType + ": " + e.getErrorCode() + " - " + e.getMessage());
    
            if (e.getCause() != null) {
                log("Connection failure cause: " + e.getCause().getMessage());
            }
            return false;
        }
    }

    public void dispose() {
        statementCache.invalidateAll();
        ds.close();
        connected = false;
    }

    /**
     * @return the connection to the database
     */
    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    /**
     * @return the path where the database file should be saved
     */
    public String getDatabasePath() {
        Configuration lwcConfiguration = LWC.getInstance().getConfiguration();

        if (currentType == Type.MySQL) {
            return "//" + lwcConfiguration.getString("database.host") + "/" + lwcConfiguration.getString("database.database");
        }

        return lwcConfiguration.getString("database.path");
    }

    /**
     * @return the database engine type
     */
    public Type getType() {
        return currentType;
    }

    /**
     * Load the database
     */
    public abstract void load();

    /**
     * Log a string to stdout
     *
     * @param str The string to log
     */
    public void log(String str) {
        LWC.getInstance().log(str);
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql       The SQL to execute
     * @param callback  What to do with the prepared statement
     * @return what the callback returns or null if not connected
     */
    public <T> T prepare(String sql, ThrowingCallback<T, PreparedStatement> callback) {
        return prepare(sql, false, callback);
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql       The SQL to execute
     * @param consumer  What to do with the prepared statement
     */
    public void prepare(String sql, ThrowingConsumer<PreparedStatement> consumer) {
        prepare(sql, false, statement -> {
            consumer.accept(statement);
            return null;
        });
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql       The SQL to execute
     * @param consumer  What to do with the prepared statement
     * @param throwableConsumer     What to do if an error is thrown
     */
    public void prepare(String sql, ThrowingConsumer<PreparedStatement> consumer, Consumer<Throwable> throwableConsumer) {
        prepare(sql, false, statement -> {
            consumer.accept(statement);
            return null;
        }, throwableConsumer);
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql                   The SQL to execute
     * @param returnGeneratedKeys   Whether or not to return the generated keys
     * @param consumer  What to do with the prepared statement
     */
    public void prepare(String sql, boolean returnGeneratedKeys, ThrowingConsumer<PreparedStatement> consumer) {
        prepare(sql, returnGeneratedKeys, statement -> {
            consumer.accept(statement);
            return null;
        }, throwable -> {
            throw new ModuleException("Failed to run prepared statement " + sql, throwable);
        });
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql       The SQL to execute
     * @param callback  What to do with the prepared statement
     * @param throwableConsumer     What to do if an error is thrown
     * @return what the callback returns or null if not connected
     */
    public <T> T prepare(String sql, ThrowingCallback<T, PreparedStatement> callback, Consumer<Throwable> throwableConsumer) {
        return prepare(sql, false, callback, throwableConsumer);
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql                   The SQL to execute
     * @param returnGeneratedKeys   Whether or not to return the generated keys
     * @param callback              What to do with the prepared statement
     * @return what the callback returns or null if not connected
     */
    public <T> T prepare(String sql, boolean returnGeneratedKeys, ThrowingCallback<T, PreparedStatement> callback) {
        return prepare(sql, returnGeneratedKeys, callback, throwable -> {
            throw new ModuleException("Failed to run prepared statement " + sql, throwable);
        });
    }

    /**
     * Prepare a statement unless it's already cached and consume it
     *
     * @param sql                   The SQL to execute
     * @param returnGeneratedKeys   Whether or not to return the generated keys
     * @param callback              What to do with the prepared statement
     * @param throwableConsumer     What to do if an error is thrown
     * @return what the callback returns or null if not connected
     */
    public <T> T prepare(String sql, boolean returnGeneratedKeys, ThrowingCallback<T, PreparedStatement> callback, Consumer<Throwable> throwableConsumer) {
        if (!ds.isRunning() || ds.isClosed()) {
            return null;
        }

        try {
            if (useStatementCache) {
                Statistics.addQuery();
                final PreparedStatement p = statementCache.getIfPresent(sql);
                if(p != null && !p.isClosed()) {
                    return callback.call(p);
                }
            }

            try (Connection connection = ds.getConnection()) {
                PreparedStatement preparedStatement;

                if (returnGeneratedKeys) {
                    preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                } else {
                    preparedStatement = connection.prepareStatement(sql);
                }

                if (useStatementCache) {
                    statementCache.put(sql, preparedStatement);
                }

                Statistics.addQuery();
                return callback.call(preparedStatement);
            }
        } catch (Throwable ex) {
            throwableConsumer.accept(ex);
            return null;
        }
    }

    /**
     * Add a column to a table
     *
     * @param table
     * @param column
     */
    public boolean addColumn(String table, String column, String type) {
        return executeUpdateNoException("ALTER TABLE " + table + " ADD " + column + " " + type);
    }

    /**
     * Add a column to a table
     *
     * @param table
     * @param column
     */
    public boolean dropColumn(String table, String column) {
        return executeUpdateNoException("ALTER TABLE " + table + " DROP COLUMN " + column);
    }

    /**
     * Rename a table
     *
     * @param table
     * @param newName
     */
    public boolean renameTable(String table, String newName) {
        return executeUpdateNoException("ALTER TABLE " + table + " RENAME TO " + newName);
    }

    /**
     * Drop a table
     *
     * @param table
     */
    public boolean dropTable(String table) {
        return executeUpdateNoException("DROP TABLE " + table);
    }

    /**
     * Execute an update, ignoring any exceptions
     *
     * @param query
     * @return true if an exception was thrown
     */
    public boolean executeUpdateNoException(String query) {
        boolean exception = false;

        try (Connection connection = ds.getConnection()) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(query);
        } catch (SQLException e) {
            exception = true;
        }

        return exception;
    }

    /**
     * @return true if connected to the database
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Returns true if the high level statement cache should be used. If this is false, already cached statements are ignored
     *
     * @return
     */
    public boolean useStatementCache() {
        return useStatementCache;
    }

    /**
     * Set if the high level statement cache should be used.
     *
     * @param useStatementCache
     * @return
     */
    public void setUseStatementCache(boolean useStatementCache) {
        this.useStatementCache = useStatementCache;
    }

}
