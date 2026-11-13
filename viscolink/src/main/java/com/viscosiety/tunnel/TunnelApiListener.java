/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.viscosiety.tunnel;

import org.frankframework.http.rest.ApiListener;

/**
 * An {@link ApiListener} that answers on an HTTPS tunnel port only: a system at a customer's site reaches it
 * through the account's private tunnel, and the public address answers 404 for it. Same attributes as
 * ApiListener. Use it as {@code <Listener className="com.viscosiety.tunnel.TunnelApiListener" .../>}.
 * The same uriPattern and method cannot be declared on an ApiListener as well.
 */
public class TunnelApiListener extends ApiListener {
}
