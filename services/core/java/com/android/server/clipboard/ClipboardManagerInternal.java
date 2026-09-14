/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.clipboard;

/**
 * Internal interface for the clipboard manager.
 */
public interface ClipboardManagerInternal {

    /**
     * Records a trusted user action for clipboard access notification bookkeeping.
     *
     * <p>This suppresses a redundant user-visible access notification and marks subsequent access
     * logging as user initiated. It does not grant permission to read clipboard data.</p>
     *
     * @param uid The uid expected to access clip data.
     */
    void notifyUserAuthorizedClipAccess(int uid);

    /**
     * Creates a short-lived clipboard read grant for the UID targeted on {@code displayId}.
     *
     * <p>The caller must validate both an approved trusted paste trigger and its exact target, and
     * must create the grant synchronously before dispatching the paste action to that target. The
     * grant is bound to the resolved clipboard device and current primary clip generation. It does
     * not bypass the normal clipboard package identity, focus, AppOps, or device lock checks. The
     * resulting authorization is UID scoped rather than tied to the route-specific input
     * connection or window used to validate the action.</p>
     *
     */
    void createPasteGrantForDisplay(int uid, int displayId);
}
