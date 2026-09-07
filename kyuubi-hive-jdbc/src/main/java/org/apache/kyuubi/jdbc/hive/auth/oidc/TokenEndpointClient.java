package org.apache.kyuubi.jdbc.hive.auth.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;

public class TokenEndpointClient {

  private final OidcProviderMetadata metadata;
  private final OidcConfig config;
  private final HttpJsonClient http;

  public TokenEndpointClient(
      OidcProviderMetadata metadata, OidcConfig config, HttpJsonClient http) {
    this.metadata = metadata;
    this.config = config;
    this.http = http;
  }

  public OidcTokens exchangeAuthorizationCode(
      String code, String codeVerifier, String redirectUri) {
    Map<String, String> form = baseForm();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("code_verifier", codeVerifier);
    form.put("redirect_uri", redirectUri);
    HttpJsonClient.Response r = post(form);
    requireSuccess(r, "authorization code exchange");
    return parseTokens(r.body);
  }

  public OidcTokens refresh(String refreshToken) {
    Map<String, String> form = baseForm();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    if (config.scope() != null) {
      form.put("scope", config.scope());
    }
    HttpJsonClient.Response r = post(form);
    requireSuccess(r, "token refresh");
    return parseTokens(r.body);
  }

  public HttpJsonClient.Response pollDeviceToken(String deviceCode) {
    Map<String, String> form = baseForm();
    form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
    form.put("device_code", deviceCode);
    return post(form);
  }

  private Map<String, String> baseForm() {
    Map<String, String> form = new HashMap<>();
    form.put("client_id", config.clientId());
    return form;
  }

  private HttpJsonClient.Response post(Map<String, String> form) {
    if (config.isConfidentialClient()) {
      return http.postForm(
          metadata.tokenEndpoint(), form, config.clientId(), config.clientSecret());
    }
    return http.postForm(metadata.tokenEndpoint(), form, null, null);
  }

  static OidcTokens parseTokens(JsonNode body) {
    String accessToken = text(body, "access_token");
    if (accessToken == null) {
      throw new OidcAuthException("Token response did not contain an access_token");
    }
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong(0L) : 0L;
    return OidcTokens.of(
        accessToken,
        text(body, "refresh_token"),
        text(body, "id_token"),
        expiresIn,
        System.currentTimeMillis());
  }

  private static void requireSuccess(HttpJsonClient.Response r, String what) {
    if (!r.isSuccess()) {
      String error = text(r.body, "error");
      String desc = text(r.body, "error_description");
      throw new OidcAuthException(
          "OIDC "
              + what
              + " failed (HTTP "
              + r.status
              + "): "
              + error
              + " "
              + (desc == null ? "" : desc));
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
