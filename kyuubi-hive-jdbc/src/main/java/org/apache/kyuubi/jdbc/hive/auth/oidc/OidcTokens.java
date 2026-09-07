package org.apache.kyuubi.jdbc.hive.auth.oidc;
/
public class OidcTokens {

  private final String accessToken;
  private final String refreshToken;
  private final String idToken;
  private final long expiresAtEpochMs;

  public OidcTokens(
      String accessToken, String refreshToken, String idToken, long expiresAtEpochMs) {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.idToken = idToken;
    this.expiresAtEpochMs = expiresAtEpochMs;
  }

  /**
   * @param expiresInSeconds the {@code expires_in} value from the token response; may be {@code <=0} if 
   * the provider omitted it, in which case the token is treated as already near expiry.
   */
  public static OidcTokens of(
      String accessToken,
      String refreshToken,
      String idToken,
      long expiresInSeconds,
      long nowEpochMs) {
    long expiresAt = expiresInSeconds > 0 ? nowEpochMs + (expiresInSeconds * 1000L) : nowEpochMs;
    return new OidcTokens(accessToken, refreshToken, idToken, expiresAt);
  }

  public String accessToken() {
    return accessToken;
  }

  public String refreshToken() {
    return refreshToken;
  }

  public String idToken() {
    return idToken;
  }

  public boolean hasRefreshToken() {
    return refreshToken != null && !refreshToken.isEmpty();
  }

  /**
   * @return true when the access token is expired or within {@code skewMs} of expiry, and should be
   *     refreshed before use.
   */
  public boolean isExpiredOrNearExpiry(long nowEpochMs, long skewMs) {
    return nowEpochMs >= (expiresAtEpochMs - skewMs);
  }
}
