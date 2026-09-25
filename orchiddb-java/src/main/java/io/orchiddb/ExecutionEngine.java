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

    QueryResult execute(CompiledQuery query) throws SQLException;

    void close() throws SQLException;
  }
}
