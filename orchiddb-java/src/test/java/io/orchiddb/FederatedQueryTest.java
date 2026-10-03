package io.orchiddb;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FederatedQueryTest {
  @Test
  void postgresAndDuckdbIslandsInBothDirections() throws Exception {
    String url = System.getenv("ORCHIDDB_TEST_PG_JDBC");
    assumeTrue(url != null, "ORCHIDDB_TEST_PG_JDBC enables real PostgreSQL conformance");
    var compiler = NativeSqlCompiler.load(Path.of(System.getProperty("orchiddb.native.path")));
    try (var pg = DriverManager.getConnection(url);
        var duck = DriverManager.getConnection("jdbc:duckdb:")) {
      for (var connection : List.of(pg, duck))
        try (var s = connection.createStatement()) {
          s.execute("CREATE TEMP TABLE people(id BIGINT, name VARCHAR, age BIGINT)");
          s.execute("INSERT INTO people VALUES (1,'Ada',30),(2,'Bob',20)");
          s.execute("CREATE TEMP TABLE links(id BIGINT, src BIGINT, dst BIGINT)");
          s.execute("INSERT INTO links VALUES (1,1,2)");
        }
      var engines =
          Map.<String, ExecutionEngine>of(
              "p",
              JdbcEngine.borrowed("p", SqlDialect.POSTGRES, pg),
              "d",
              JdbcEngine.borrowed("d", SqlDialect.DUCKDB, duck));
      var json = new ObjectMapper();
      for (String target : List.of("p", "d")) {
        var request =
            json.readTree(
                """
          {"version":1,"dialect":"duckdb","execution_engine":"d",
           "engines":{"d":{"dialect":"duckdb"},"p":{"dialect":"postgres"}},
           "language":"cypher","query":"MATCH (a:Person)-[:KNOWS]->(b:Person) WHERE a.age > 25 RETURN a.name AS a, b.name AS b",
           "tables":[{"name":"people","engine":"p","columns":[{"name":"id","data_type":"int64"},{"name":"name","data_type":"string"},{"name":"age","data_type":"int64"}]},
             {"name":"links","engine":"d","columns":[{"name":"id","data_type":"int64"},{"name":"src","data_type":"int64"},{"name":"dst","data_type":"int64"}]}],
           "nodes":[{"label":"Person","table":"people","id":"id","properties":{"name":"name","age":"age"}}],
           "edges":[{"label":"KNOWS","table":"links","id":"id","source":"src","target":"dst","source_label":"Person","target_label":"Person"}]}
          """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) request)
            .put("execution_engine", target)
            .put("dialect", target.equals("p") ? "postgres" : "duckdb");
        try (var rows = FederatedQuery.query(compiler, request.toString(), engines)) {
          assertTrue(rows.next());
          assertEquals("Ada", rows.get(1));
          assertEquals("Bob", rows.get(2));
          assertFalse(rows.next());
        }
        for (var connection : List.of(pg, duck))
          try (var s = connection.createStatement();
              var r =
                  s.executeQuery(
                      "SELECT count(*) FROM information_schema.tables WHERE table_name LIKE '__orchiddb_exchange_%'")) {
            r.next();
            assertEquals(0, r.getLong(1));
          }
      }
      try (var s = pg.createStatement()) {
        s.execute("CREATE TEMP TABLE nested_values(id BIGINT, items JSONB[])");
        s.execute(
            "INSERT INTO nested_values VALUES (1, ARRAY['[1]'::jsonb,'[2,3]'::jsonb,NULL,'[]'::jsonb])");
      }
      try (var s = duck.createStatement()) {
        s.execute("CREATE TEMP TABLE nested_values(id BIGINT, items BIGINT[][])");
        s.execute("INSERT INTO nested_values VALUES (1, [[1],[2,3],NULL,[]])");
      }
      for (String target : List.of("p", "d")) {
        var request =
            json.readTree(
                """
            {"version":1,"language":"cypher","query":"MATCH (n:Nested) RETURN ncount(n.items) AS n",
             "engines":{"d":{"dialect":"duckdb"},"p":{"dialect":"postgres"}},
             "tables":[{"name":"nested_values","columns":[{"name":"id","data_type":"int64"},{"name":"items","data_type":"list:list:int64"}]}],
             "nodes":[{"label":"Nested","table":"nested_values","id":"id","properties":{"items":"items"}}],
             "functions":[{"name":"ncount","parameters":["list:list:int64"],"returns":"int64"}]}
            """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) request)
            .put("execution_engine", target)
            .put("dialect", target.equals("p") ? "postgres" : "duckdb");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.get("tables").get(0))
            .put("engine", target.equals("p") ? "d" : "p");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.get("functions").get(0))
            .put("target", target.equals("p") ? "cardinality" : "len");
        try (var rows = FederatedQuery.query(compiler, request.toString(), engines)) {
          assertTrue(rows.next());
          assertEquals(4, ((Number) rows.get(1)).longValue());
          assertFalse(rows.next());
        }
      }
      assertFalse(pg.isClosed());
      assertFalse(duck.isClosed());
    }
  }
}
