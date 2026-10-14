package emon.api;

/** The test hooks of the Java host (P11.4, D1480): the package-private settings the gates' driver sets. NOT part of the published jar:
 *  kin/scripts/emon-java-build copies this file into the driver's tree only. */
public final class TestHooks {
    private TestHooks() {
    }

    /** `Options.noRetarget` (P7.4): leave every pixel unread by the page units. */
    public static void setNoRetarget(Options o, boolean v) {
        o.noRetarget = v;
    }
}
