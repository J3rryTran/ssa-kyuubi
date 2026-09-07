package org.apache.kyuubi.jdbc.hive.auth.oidc;

public class OidcAuthException extends RuntimeException {

  public OidcAuthException(String message) {
    super(message);
  }

  public OidcAuthException(String message, Throwable cause) {
    super(message, cause);
  }
}
