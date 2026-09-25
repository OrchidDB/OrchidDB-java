package io.orchiddb;

import java.sql.SQLException;
import java.util.*;

/** Immutable engine registry. Owns no engines, pools or connections. */
public final class OrchidDB {
  private final SqlCompiler compiler;
  private final PlanCache cache;
  private final Map<String, ExecutionEngine> engines;

  public OrchidDB(SqlCompiler compiler, PlanCache cache, ExecutionEngine... engines) {
    this.compiler = Objects.requireNonNull(compiler);
    this.cache = Objects.requireNonNull(cache);
    var map = new HashMap<String, ExecutionEngine>();
    for (var engine : engines)
      if (map.putIfAbsent(engine.id(), engine) != null)
        throw new IllegalArgumentException("Duplicate engine " + engine.id());
    this.engines = Map.copyOf(map);
  }

  public Graph graph(GraphMapping mapping) {
    return graph(mapping, List.of());
  }

  public Graph graph(GraphMapping mapping, List<FunctionSignature> functions) {
    String id = mapping.singleEngine();
    var engine = engines.get(id);
    if (engine == null) throw new PlanningException("Unknown engine " + id);
    return new Graph(engine, mapping, List.copyOf(functions));
  }

  public final class Graph {
    private final ExecutionEngine engine;
    private final GraphMapping mapping;
    private final List<FunctionSignature> functions;

    private Graph(ExecutionEngine e, GraphMapping m, List<FunctionSignature> f) {
      engine = e;
      mapping = m;
      functions = f;
    }

    private CompiledQuery compile(ExecutionEngine.Session session, Query query)
        throws SQLException {
      var request =
          new Compilation(
              engine.id(), engine.dialect(), mapping, session.schemas(mapping), functions, query);
      var plan = cache.get(request);
      if (plan == null) {
        plan = compiler.compile(request);
        validate(plan);
        cache.put(request, plan);
      }
      validate(plan);
      return plan;
    }

    private void validate(CompiledQuery plan) {
      if (!plan.engine().equals(engine.id()) || !plan.dialect().equals(engine.dialect()))
        throw new PlanningException("Compiler/cache returned a plan for another engine");
    }

    public CompiledQuery plan(Query query) throws SQLException {
      try (var session = engine.openSession()) {
        return compile(session, query);
      }
    }

    public QueryResult query(Query query) throws SQLException {
      var session = engine.openSession();
      try {
        var result = session.execute(compile(session, query));
        return new QueryResult() {
          private boolean closed;

          public List<String> columns() {
            return result.columns();
          }

          public boolean next() throws SQLException {
            return result.next();
          }

          public Object get(int column) throws SQLException {
            return result.get(column);
          }

          public void close() throws SQLException {
            if (closed) return;
            closed = true;
            try {
              result.close();
            } catch (SQLException | RuntimeException | Error e) {
              try {
                session.close();
              } catch (Exception x) {
                e.addSuppressed(x);
              }
              throw e;
            }
            session.close();
          }
        };
      } catch (SQLException | RuntimeException | Error e) {
        try {
          session.close();
        } catch (Exception x) {
          e.addSuppressed(x);
        }
        throw e;
      }
    }
  }
}
