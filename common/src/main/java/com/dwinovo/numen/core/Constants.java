package com.dwinovo.numen.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity constants for the Momo gameplay tool pack. Momo Engine has its own
 * {@code com.dwinovo.numen.Constants}; this is
 * deliberately a separate type in a separate package so the two mods never put
 * the same fully-qualified class on the runtime classpath.
 */
public final class Constants {

    public static final String MOD_ID = "momo_gameplay";
    public static final String MOD_NAME = "Momo Gameplay";
    /** Existing datapacks and tags remain under data/numen. */
    public static final String CONTENT_NAMESPACE = "numen";
    public static final Logger LOG = LoggerFactory.getLogger("MomoGameplay");

    private Constants() {}
}
