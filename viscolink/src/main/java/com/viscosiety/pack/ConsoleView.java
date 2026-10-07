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

package com.viscosiety.pack;

/**
 * An extra Frank!Console view, in the shape of the {@code customViews.*} properties.
 *
 * @param name   the label of the view
 * @param url    where the view lives
 * @param target the browsing context it opens in (e.g. {@code _self}), possibly null
 */
public record ConsoleView(String name, String url, String target) {
}
