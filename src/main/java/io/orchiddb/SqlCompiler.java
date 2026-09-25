package io.orchiddb;

@FunctionalInterface
public interface SqlCompiler {
  CompiledQuery compile(Compilation request);
}
