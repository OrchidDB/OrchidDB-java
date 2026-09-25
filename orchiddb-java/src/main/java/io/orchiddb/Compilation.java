package io.orchiddb;

import java.util.*;

/** Complete immutable compilation snapshot. Also usable without a database connection. */
public record Compilation(
    String engine,
    SqlDialect dialect,
    GraphMapping mapping,
    Map<Source, List<Column>> schemas,
    List<FunctionSignature> functions,
    Query query) {
  public Compilation {
    Checks.name(engine);
    Objects.requireNonNull(dialect);
    Objects.requireNonNull(mapping);
    Objects.requireNonNull(query);
    if (!engine.equals(mapping.singleEngine()))
      throw new PlanningException("Mapping is bound to a different engine");
    var copy = new LinkedHashMap<Source, List<Column>>();
    schemas.forEach((s, c) -> copy.put(s, List.copyOf(c)));
    if (!copy.keySet().equals(mapping.sources()))
      throw new IllegalArgumentException("Supply exactly the mapped source schemas");
    schemas = Collections.unmodifiableMap(copy);
    functions = List.copyOf(functions);
  }
}
