package org.junit;
public class Assert {
  public static void assertTrue(boolean v) { if (!v) throw new AssertionError("expected true"); }
  public static void assertFalse(boolean v) { if (v) throw new AssertionError("expected false"); }
  public static void assertNull(Object v) { if (v != null) throw new AssertionError("expected null, got " + v); }
  public static void assertNotNull(Object v) { if (v == null) throw new AssertionError("expected non-null"); }
  public static void assertEquals(Object e, Object a) { if (e == null ? a != null : !e.equals(a)) throw new AssertionError("expected <" + e + "> but was <" + a + ">"); }
  public static void assertNotEquals(Object e, Object a) { if (e == null ? a == null : e.equals(a)) throw new AssertionError("did not expect <" + a + ">"); }
}
