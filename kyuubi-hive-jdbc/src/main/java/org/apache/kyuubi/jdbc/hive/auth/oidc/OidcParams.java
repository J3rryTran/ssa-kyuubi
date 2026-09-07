package org.apache.kyuubi.jdbc.hive.auth.oidc;

public final class OidcParams {
  private OidcParams() {}
  public static final String AUTH_TYPE_OIDC = "oidc";
  public static final String OIDC_ENABLED = "oidc";
  public static final String OIDC_ISSUER = "oidcIssuer";
  public static final String OIDC_DISCOVERY_URI = "oidcDiscoveryUri";
  public static final String OIDC_CLIENT_ID = "oidcClientId";
  public static final String OIDC_CLIENT_SECRET = "oidcClientSecret";
  public static final String OIDC_SCOPE = "oidcScope";
  public static final String OIDC_FLOW = "oidcFlow";
  public static final String OIDC_REDIRECT_PORT = "oidcRedirectPort";
  public static final String OIDC_TOKEN_CACHE = "oidcTokenCache";
  public static final String OIDC_BROWSER = "oidcBrowser";
  public static final String OIDC_LOGOUT = "oidcLogout";
  public static final String OIDC_INSECURE_TLS = "oidcInsecureTls";
}
