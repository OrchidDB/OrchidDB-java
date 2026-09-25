package io.orchiddb.internal;

/** Internal versioned boundary; no native database handles. */
public final class NativeBridge {
  private NativeBridge() {}

  public static native String compileJson(String request);
}
