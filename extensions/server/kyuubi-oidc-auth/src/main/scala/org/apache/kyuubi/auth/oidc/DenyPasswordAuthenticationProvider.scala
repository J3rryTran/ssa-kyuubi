package org.apache.kyuubi.auth.oidc

import javax.security.sasl.AuthenticationException

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.PasswdAuthenticationProvider


class DenyPasswordAuthenticationProvider(conf: KyuubiConf) extends PasswdAuthenticationProvider {

  def this() = this(null)

  override def authenticate(user: String, password: String): Unit = {
    throw new AuthenticationException(
      "Password authentication is disabled; use OIDC/JWT bearer authentication over HTTP transport")
  }
}
