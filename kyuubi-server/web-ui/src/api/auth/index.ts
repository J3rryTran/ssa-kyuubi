/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import request from '@/utils/request'

export interface AuthConfig {
  authType: string
  oidcEnabled: boolean
  issuer?: string
  clientId?: string
  scope?: string
  /**
   * 'bff'    — the server holds the tokens and the browser only gets a session cookie.
   * 'public' — the browser runs the PKCE flow itself and holds the tokens in memory.
   */
  flow?: 'bff' | 'public'
  /** Only meaningful under 'bff', where the browser cannot tell on its own. */
  authenticated?: boolean
  username?: string | null
}

export interface LogoutResult {
  /** Where to go to end the provider's own session, when it publishes such an endpoint. */
  endSessionUrl?: string | null
}

/**
 * Fetch how this server expects the UI to authenticate. The endpoint is
 * deliberately unauthenticated — it only returns public OIDC discovery inputs.
 */
export function getAuthConfig(): Promise<AuthConfig> {
  return request({
    url: 'api/v1/authentication/config',
    method: 'get'
  }) as unknown as Promise<AuthConfig>
}

/** Drop the server-side session behind the cookie. Only used by the 'bff' flow. */
export function logoutSession(): Promise<LogoutResult> {
  return request({
    url: 'api/v1/authentication/logout',
    method: 'post'
  }) as unknown as Promise<LogoutResult>
}
