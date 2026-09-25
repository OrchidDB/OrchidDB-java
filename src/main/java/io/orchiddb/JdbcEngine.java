package io.orchiddb;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** JDBC execution adapter. Borrowing preserves the exact session supplied by the application. */
public final class JdbcEngine implements ExecutionEngine {
  private final String id;
  private final SqlDialect dialect;
  private final Connection borrowed;
  private final DataSource pool;
  private final AtomicBoolean inUse = new AtomicBoolean();
  private final int fetchSize;
  private final int timeoutSeconds;

  private JdbcEngine(
      String id,
      SqlDialect dialect,
      Connection borrowed,
      DataSource pool,
      int fetchSize,
      int timeoutSeconds) {
    this.id = Checks.name(id);
    this.dialect = Objects.requireNonNull(dialect);
    this.borrowed = borrowed;
    this.pool = pool;
    if (fetchSize < 0 || timeoutSeconds < 0)
      throw new IllegalArgumentException("Negative execution setting");
    this.fetchSize = fetchSize;
    this.timeoutSeconds = timeoutSeconds;
  }

  public static JdbcEngine borrowed(String id, SqlDialect dialect, Connection connection) {
    return new JdbcEngine(id, dialect, Objects.requireNonNull(connection), null, 0, 0);
  }

  public static JdbcEngine pooled(String id, SqlDialect dialect, DataSource pool) {
    return new JdbcEngine(id, dialect, null, Objects.requireNonNull(pool), 0, 0);
  }

  public static JdbcEngine borrowed(
      String id, SqlDialect dialect, Connection connection, int fetchSize, int timeoutSeconds) {
    return new JdbcEngine(
        id, dialect, Objects.requireNonNull(connection), null, fetchSize, timeoutSeconds);
  }

  public static JdbcEngine pooled(
      String id, SqlDialect dialect, DataSource pool, int fetchSize, int timeoutSeconds) {
    return new JdbcEngine(
        id, dialect, null, Objects.requireNonNull(pool), fetchSize, timeoutSeconds);
  }

  public String id() {
    return id;
  }

  public SqlDialect dialect() {
    return dialect;
  }

  public Session openSession() throws SQLException {
    if (borrowed != null && !inUse.compareAndSet(false, true))
      throw new SQLException(
          "Engine "
              + id
              + " already has an active session; close its result before reusing this borrowed connection");
    try {
      return new JdbcSession(borrowed != null ? borrowed : pool.getConnection());
    } catch (SQLException | RuntimeException | Error e) {
      if (borrowed != null) inUse.set(false);
      throw e;
    }
  }

  private final class JdbcSession implements Session {
    private final Connection connection;
    private final Set<Cursor> cursors = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    JdbcSession(Connection connection) {
      this.connection = Objects.requireNonNull(connection);
    }

    private void checkOpen() throws SQLException {
      if (closed) throw new SQLException("Session is closed");
    }

    public Map<Source, List<Column>> schemas(Set<Source> sources) throws SQLException {
      checkOpen();
      var result = new LinkedHashMap<Source, List<Column>>();
      for (var source : sources) {
        if (!source.engine().equals(id))
          throw new SQLException("Source is bound to a different engine");
        // Metadata is discovered in this session. No views, tables or transactions are changed.
        try (var statement = connection.createStatement()) {
          if (timeoutSeconds > 0) statement.setQueryTimeout(timeoutSeconds);
          try (var rows =
              statement.executeQuery("SELECT * FROM " + source.sql(dialect) + " WHERE 1 = 0")) {
            var meta = rows.getMetaData();
            var columns = new ArrayList<Column>();
            for (int i = 1; i <= meta.getColumnCount(); i++)
              columns.add(
                  new Column(
                      meta.getColumnName(i),
                      type(meta, i),
                      meta.isNullable(i) != ResultSetMetaData.columnNoNulls));
            result.put(source, List.copyOf(columns));
          }
        }
      }
      return Collections.unmodifiableMap(result);
    }

    public QueryResult execute(CompiledQuery query) throws SQLException {
      checkOpen();
      if (!query.engine().equals(id) || !query.dialect().equals(dialect))
        throw new SQLException("Plan is bound to a different engine or dialect");
      var statement = connection.createStatement();
      try {
        if (fetchSize > 0) statement.setFetchSize(fetchSize);
        if (timeoutSeconds > 0) statement.setQueryTimeout(timeoutSeconds);
        var rows = statement.executeQuery(query.sql());
        var cursor = new Cursor(statement, rows);
        cursors.add(cursor);
        return cursor;
      } catch (SQLException | RuntimeException | Error e) {
        try {
          statement.close();
        } catch (SQLException x) {
          e.addSuppressed(x);
        }
        throw e;
      }
    }

    public void close() throws SQLException {
      if (closed) return;
      closed = true;
      SQLException failure = null;
      for (var cursor : List.copyOf(cursors)) {
        try {
          cursor.close();
        } catch (SQLException e) {
          if (failure == null) failure = e;
          else failure.addSuppressed(e);
        }
      }
      try {
        if (borrowed == null) connection.close();
      } catch (SQLException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      } finally {
        if (borrowed != null) inUse.set(false);
      }
      if (failure != null) throw failure;
    }

    private final class Cursor implements QueryResult {
      private final Statement statement;
      private final ResultSet rows;
      private final List<String> columns;
      private boolean closed;

      Cursor(Statement statement, ResultSet rows) throws SQLException {
        this.statement = statement;
        this.rows = rows;
        var labels = new ArrayList<String>();
        var metadata = rows.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) labels.add(metadata.getColumnLabel(i));
        columns = List.copyOf(labels);
      }

      public List<String> columns() {
        return columns;
      }

      public boolean next() throws SQLException {
        if (closed) throw new SQLException("Result is closed");
        return rows.next();
      }

      public Object get(int column) throws SQLException {
        if (closed) throw new SQLException("Result is closed");
        return rows.getObject(column);
      }

      public void close() throws SQLException {
        if (closed) return;
        closed = true;
        cursors.remove(this);
        try {
          rows.close();
        } catch (SQLException e) {
          try {
            statement.close();
          } catch (SQLException x) {
            e.addSuppressed(x);
          }
          throw e;
        }
        statement.close();
      }
    }
  }

  private static String type(ResultSetMetaData m, int i) throws SQLException {
    // JDBC's integer code alone cannot distinguish DuckDB unsigned values.
    String nativeType = m.getColumnTypeName(i).toUpperCase(Locale.ROOT);
    if (nativeType.startsWith("U")
        && Set.of("UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT").contains(nativeType))
      throw new SQLException(
          "Unsupported unsigned column " + m.getColumnName(i) + "; cast it in a view");
    if (m.getColumnType(i) == Types.TIMESTAMP
        && (nativeType.equals("TIMESTAMP_NS") || m.getScale(i) > 6)) {
      throw new SQLException(
          "Timestamp column "
              + m.getColumnName(i)
              + " exceeds microsecond precision; cast it explicitly in a view");
    }
    return switch (m.getColumnType(i)) {
      case Types.BOOLEAN, Types.BIT -> "boolean";
      case Types.TINYINT -> "int8";
      case Types.SMALLINT -> "int16";
      case Types.INTEGER -> "int32";
      case Types.BIGINT -> "int64";
      case Types.REAL -> "float32";
      case Types.FLOAT, Types.DOUBLE -> "float64";
      case Types.CHAR,
              Types.VARCHAR,
              Types.LONGVARCHAR,
              Types.NCHAR,
              Types.NVARCHAR,
              Types.LONGNVARCHAR ->
          "string";
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "binary";
      case Types.DATE -> "date";
      case Types.TIMESTAMP -> "timestamp";
      case Types.NUMERIC, Types.DECIMAL -> "decimal:" + m.getPrecision(i) + ":" + m.getScale(i);
      default ->
          throw new SQLException(
              "Unsupported JDBC column "
                  + m.getColumnName(i)
                  + " ("
                  + nativeType
                  + "); cast it in a source view");
    };
  }
}
