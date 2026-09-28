package io.orchiddb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orchiddb.internal.NativeBridge;
import io.orchiddb.internal.NativeLibrary;
import java.nio.file.Path;
import java.util.*;

/** Shared graph compiler and statistics coordinator. */
public final class NativeSqlCompiler implements SqlCompiler {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static Path loadedPath;

  private NativeSqlCompiler() {}

  /**
   * Loads the explicitly configured path, or the matching native classifier JAR on the classpath.
   */
  public static NativeSqlCompiler load() {
    String override = System.getProperty("orchiddb.native.path");
    return load(
        override == null || override.isBlank() ? NativeLibrary.extract() : Path.of(override));
  }

  public static synchronized NativeSqlCompiler load(Path library) {
    Path path = library.toAbsolutePath().normalize();
    if (loadedPath != null && !loadedPath.equals(path))
      throw new IllegalStateException("A different native compiler is already loaded");
    if (loadedPath == null) {
      try {
        System.load(path.toString());
      } catch (UnsatisfiedLinkError e) {
        throw new PlanningException(
            "Cannot load OrchidDB compiler at "
                + path
                + ". Run scripts/build.sh for this platform.",
            e);
      }
      loadedPath = path;
    }
    return new NativeSqlCompiler();
  }

  static Map<String, Object> request(Compilation r) {
    var request = new LinkedHashMap<String, Object>();
    request.put("version", 1);
    request.put("dialect", r.dialect().id());
    request.put("language", r.query().language());
    request.put("query", r.query().text());
    request.put("parameters", r.query().parameters());
    request.put("ontology", r.query().ontology());
    if (!r.query().rdf().isEmpty()) request.put("rdf", r.query().rdf());
    if (!r.query().dataset().equals("default")) request.put("dataset", r.query().dataset());
    request.put(
        "tables",
        r.schemas().entrySet().stream()
            .map(
                e ->
                    Map.of(
                        "name",
                        e.getKey().sql(r.dialect()),
                        "columns",
                        e.getValue().stream()
                            .map(
                                c ->
                                    Map.of(
                                        "name",
                                        c.name(),
                                        "data_type",
                                        c.dataType(),
                                        "nullable",
                                        c.nullable()))
                            .toList()))
            .toList());
    request.put(
        "nodes",
        r.mapping().nodes().stream()
            .map(
                n ->
                    Map.of(
                        "label",
                        n.label(),
                        "table",
                        n.source().sql(r.dialect()),
                        "id",
                        n.id(),
                        "properties",
                        n.properties()))
            .toList());
    request.put(
        "edges",
        r.mapping().edges().stream()
            .map(
                e ->
                    Map.of(
                        "label",
                        e.label(),
                        "table",
                        e.source().sql(r.dialect()),
                        "id",
                        e.id(),
                        "source",
                        e.sourceColumn(),
                        "target",
                        e.targetColumn(),
                        "source_label",
                        e.sourceLabel(),
                        "target_label",
                        e.targetLabel(),
                        "properties",
                        e.properties()))
            .toList());
    request.put("functions", r.functions());
    return request;
  }

  public String statisticsCommand(String command) {
    return NativeBridge.statisticsJson(command);
  }

  public CompiledQuery compile(Compilation r) {
    try {
      var request = request(r);
      var result =
          JSON.readTree(
              r.statisticsCatalog() == null
                  ? NativeBridge.compileJson(JSON.writeValueAsString(request))
                  : statisticsCommand(
                      JSON.writeValueAsString(
                          Map.of(
                              "op",
                              "compile",
                              "catalog_id",
                              r.statisticsCatalog(),
                              "request",
                              request))));
      if (result.path("version").asInt() != 1
          || !result.path("dialect").asText().equals(r.dialect().id()))
        throw new PlanningException("Native compiler protocol mismatch");
      var fields = new ArrayList<String>();
      result.path("fields").forEach(f -> fields.add(f.asText()));
      return new CompiledQuery(
          r.engine(), r.dialect(), result.path("sql").asText(), fields, result.toString());
    } catch (java.io.IOException e) {
      throw new PlanningException("Invalid compiler response", e);
    }
  }
}
