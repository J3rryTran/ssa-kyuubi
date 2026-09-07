package org.apache.kyuubi.jdbc.hive.auth.oidc;

import com.fasterxml.jackson.databind.JsonNode;

public class OidcProviderMetadata {

  private final String authorizationEndpoint;
  private final String tokenEndpoint;
  private final String deviceAuthorizationEndpoint;
  private final String endSessionEndpoint;

  public OidcProviderMetadata(
      String authorizationEndpoint,
      String tokenEndpoint,
      String deviceAuthorizationEndpoint,
      String endSessionEndpoint) {
    this.authorizationEndpoint = authorizationEndpoint;
    this.tokenEndpoint = tokenEndpoint;
    this.deviceAuthorizationEndpoint = deviceAuthorizationEndpoint;
    this.endSessionEndpoint = endSessionEndpoint;
  }
  
  public static OidcProviderMetadata discover(String discoveryUrl, HttpJsonClient http) {
    JsonNode doc = http.getJson(discoveryUrl);
    String tokenEndpoint = text(doc, "token_endpoint");
    if (tokenEndpoint == null) {
      throw new OidcAuthException(
          "OIDC discovery document at " + discoveryUrl + " has no token_endpoint");
    }
    return new OidcProviderMetadata(
        text(doc, "authorization_endpoint"),
        tokenEndpoint,
        text(doc, "device_authorization_endpoint"),
        text(doc, "end_session_endpoint"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  public String authorizationEndpoint() {
    return require(authorizationEndpoint, "authorization_endpoint");
  }

  public String tokenEndpoint() {
    return tokenEndpoint;
  }

  public boolean hasDeviceAuthorizationEndpoint() {
    return deviceAuthorizationEndpoint != null;
  }

  public String deviceAuthorizationEndpoint() {
    return require(deviceAuthorizationEndpoint, "device_authorization_endpoint");
  }

  public String endSessionEndpointOrNull() {
    return endSessionEndpoint;
  }

  private static String require(String value, String name) {
    if (value == null) {
      throw new OidcAuthException("OIDC provider does not advertise " + name);
    }
    return value;
  }
}
