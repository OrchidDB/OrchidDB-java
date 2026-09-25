package io.orchiddb;

import java.sql.SQLException;
import java.util.*;

/** Transport seam: future engine adapters need not expose a JDBC connection. */
public interface ExecutionEngine {
  String id();

  SqlDialect dialect();

  Session openSession() throws SQLException;

  interface Session extends AutoCloseable {
    Map<Source, List<Column>> schemas(Set<Source> sources) throws SQLException;

    /** Adapters may inspect only the columns needed by this graph mapping. */
    default Map<Source, List<Column>> schemas(GraphMapping mapping) throws SQLException {
      return schemas(mapping.sources());
    }

    /** Batch execution; adapters must implement a native batch path or reject explicitly. */
    default ArrowResult executeArrow(CompiledQuery query) throws SQLException {
      throw new java.sql.SQLFeatureNotSupportedException("This engine has no Arrow result adapter");
    }

    /** Row convenience for Arrow adapters. Legacy row-only adapters may override this. */
    default QueryResult execute(CompiledQuery query) throws SQLException {
      return executeArrow(query).rows();
    }

    void close() throws SQLException;
  }
}
