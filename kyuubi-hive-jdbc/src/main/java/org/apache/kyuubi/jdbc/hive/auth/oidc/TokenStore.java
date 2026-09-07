package org.apache.kyuubi.jdbc.hive.auth.oidc;

import java.util.concurrent.ConcurrentHashMap;


public final class TokenStore {

  private static final ConcurrentHashMap<String, OidcTokens> CACHE = new ConcurrentHashMap<>();

  private TokenStore() {}

  public static OidcTokens get(String key) {
    return CACHE.get(key);
  }

  public static void put(String key, OidcTokens tokens) {
    if (tokens != null) {
      CACHE.put(key, tokens);
    }
  }

  public static void remove(String key) {
    CACHE.remove(key);
  }
}
