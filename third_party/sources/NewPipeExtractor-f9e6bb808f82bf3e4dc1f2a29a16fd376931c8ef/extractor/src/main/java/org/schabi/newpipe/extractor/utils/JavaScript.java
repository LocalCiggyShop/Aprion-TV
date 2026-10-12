package org.schabi.newpipe.extractor.utils;

public final class JavaScript {

    private JavaScript() {
    }

    public static void compileOrThrow(final String function) {
        throw new SecurityException("QUIETSCREEN_JS_DISABLED");
    }

    public static String run(final String function,
                             final String functionName,
                             final String... parameters) {
        throw new SecurityException("QUIETSCREEN_JS_DISABLED");
    }

}
