package io.orchiddb;

import java.util.*;

public record NodeMapping(String label, Source source, String id, Map<String, String> properties) {
  public NodeMapping {
    Checks.name(label);
    Objects.requireNonNull(source);
    Checks.name(id);
    properties = Checks.properties(properties);
  }

  public static NodeMapping node(String label, Source source, String id) {
    return new NodeMapping(label, source, id, Map.of());
  }

  public NodeMapping property(String name, String column) {
    var p = new TreeMap<>(properties);
    if (p.putIfAbsent(Checks.name(name), Checks.name(column)) != null)
      throw new IllegalArgumentException("Duplicate property " + name);
    return new NodeMapping(label, source, id, p);
  }
}
