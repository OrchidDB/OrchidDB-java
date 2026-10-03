package io.orchiddb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.*;

/**
 * Shared JSON SQL-island execution. Source data is bound into the final SELECT; no database objects
 * are created.
 */
public final class FederatedQuery {
  private FederatedQuery() {}

  public static QueryResult query(
      NativeSqlCompiler compiler, String request, Map<String, ExecutionEngine> engines)
      throws SQLException {
    var plan = compiler.compileJson(request);
    final com.fasterxml.jackson.databind.JsonNode metadata;
    try {
      metadata = new ObjectMapper().readTree(plan.diagnosticsJson());
    } catch (java.io.IOException e) {
      throw new SQLException("Invalid plan", e);
    }
    var transfers = metadata.path("transfers");
    var routes = new LinkedHashMap<String, String>();
    routes.put(plan.engine(), plan.dialect().id());
    for (var t : transfers)
      routes.put(t.path("source_engine").asText(), t.path("source_dialect").asText());
    for (var route : routes.entrySet()) {
      var engine = engines.get(route.getKey());
      if (engine == null || !engine.dialect().id().equals(route.getValue()))
        throw new SQLException("Missing engine or dialect mismatch: " + route.getKey());
    }
    var resources = new ArrayList<AutoCloseable>();
    var sessions = new HashMap<String, ExecutionEngine.Session>();
    try {
      for (var id : routes.keySet()) {
        var session = engines.get(id).openSession();
        sessions.put(id, session);
        resources.add(session);
      }
      var target = sessions.get(plan.engine());
      for (var t : transfers) {
        String sourceId = t.path("source_engine").asText();
        var fields = new ArrayList<String>();
        t.path("columns").forEach(c -> fields.add(c.path("name").asText()));
        var sourcePlan =
            new CompiledQuery(
                sourceId,
                new SqlDialect(t.path("source_dialect").asText()),
                t.path("sql").asText(),
                fields);
        try (var rows = sessions.get(sourceId).execute(sourcePlan)) {
          plan = bindRows(compiler, plan, t.path("target_relation").asText(), rows);
        }
      }
      var rows = target.execute(plan);
      resources.add(rows);
      return new QueryResult() {
        private boolean closed;

        public List<String> columns() {
          return rows.columns();
        }

        public boolean next() throws SQLException {
          return rows.next();
        }

        public Object get(int column) throws SQLException {
          return rows.get(column);
        }

        public void close() throws SQLException {
          if (!closed) {
            closed = true;
            closeAll(resources);
          }
        }
      };
    } catch (SQLException | RuntimeException | Error e) {
      try {
        closeAll(resources);
      } catch (SQLException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  static void requireSingleEngine(CompiledQuery query) throws SQLException {
    try {
      var transfers = new ObjectMapper().readTree(query.diagnosticsJson()).path("transfers");
      if (!transfers.isMissingNode() && !transfers.isEmpty())
        throw new SQLException("Use FederatedQuery for a multi-engine plan");
    } catch (java.io.IOException e) {
      throw new SQLException("Invalid compiled plan metadata", e);
    }
  }

  private static void closeAll(List<AutoCloseable> resources) throws SQLException {
    SQLException failure = null;
    for (int i = resources.size() - 1; i >= 0; i--) {
      try {
        resources.get(i).close();
      } catch (Exception e) {
        if (failure == null) failure = new SQLException("Federation cleanup failed", e);
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }

  private static CompiledQuery bindRows(
      NativeSqlCompiler compiler, CompiledQuery plan, String relation, QueryResult rows)
      throws SQLException {
    var values = new ArrayList<List<Object>>();
    while (rows.next()) {
      var row = new ArrayList<Object>();
      for (int i = 0; i < rows.columns().size(); i++) row.add(jsonValue(rows.get(i + 1)));
      values.add(row);
    }
    try {
      var json = new ObjectMapper();
      return compiler.compileJson(
          json.writeValueAsString(
              Map.of(
                  "op",
                  "bind",
                  "plan",
                  json.readTree(plan.diagnosticsJson()),
                  "relation",
                  relation,
                  "rows",
                  values,
                  "dialect",
                  plan.dialect().id(),
                  "execution_engine",
                  plan.engine())));
    } catch (java.io.IOException e) {
      throw new SQLException("Cannot encode exchange", e);
    }
  }

  private static Object jsonValue(Object value) throws SQLException {
    if (value == null || value instanceof String || value instanceof Boolean) return value;
    if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
    if (value instanceof java.sql.Array array) {
      try {
        if (array.getBaseTypeName().equalsIgnoreCase("jsonb")
            || array.getBaseTypeName().equalsIgnoreCase("json")) {
          var json =
              new ObjectMapper()
                  .enable(
                      com.fasterxml.jackson.databind.DeserializationFeature
                          .USE_BIG_DECIMAL_FOR_FLOATS)
                  .enable(
                      com.fasterxml.jackson.databind.DeserializationFeature
                          .USE_BIG_INTEGER_FOR_INTS);
          var items = array.getArray();
          var decoded = new ArrayList<Object>();
          for (int i = 0; i < java.lang.reflect.Array.getLength(items); i++) {
            var item = java.lang.reflect.Array.get(items, i);
            try {
              decoded.add(item == null ? null : json.readValue(item.toString(), Object.class));
            } catch (java.io.IOException e) {
              throw new SQLException("Invalid JSON array cell", e);
            }
          }
          return jsonValue(decoded);
        }
        return jsonValue(array.getArray());
      } finally {
        array.free();
      }
    }
    if (value instanceof Iterable<?> items) {
      var result = new ArrayList<Object>();
      for (var item : items) result.add(jsonValue(item));
      return result;
    }
    if (value.getClass().isArray()) {
      var result = new ArrayList<Object>();
      for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
        result.add(jsonValue(java.lang.reflect.Array.get(value, i)));
      return result;
    }
    if (value instanceof Number
        || value instanceof java.util.Date
        || value instanceof java.time.temporal.TemporalAccessor) return value.toString();
    throw new SQLException("Unsupported exchange value: " + value.getClass().getName());
  }
}
