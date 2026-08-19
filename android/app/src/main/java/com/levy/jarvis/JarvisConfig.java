package com.levy.jarvis;

public final class JarvisConfig {
    public static final String SECRET = "__JARVIS_SECRET__";
    public static final int HTTP_PORT = 8765;
    public static final int DISCOVERY_PORT = 47665;
    public static final String DISCOVERY_REQUEST = "JARVIS_DISCOVER_V1";
    public static final String DISCOVERY_RESPONSE = "JARVIS_CORE_V1";
    private JarvisConfig() {}
}
