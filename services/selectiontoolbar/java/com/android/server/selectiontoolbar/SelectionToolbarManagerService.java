/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.selectiontoolbar;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.service.selectiontoolbar.ISelectionToolbarRenderService;
import android.service.selectiontoolbar.ISelectionToolbarRenderServiceCallback;
import android.service.selectiontoolbar.SelectionToolbarRenderService;
import android.util.Slog;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ISelectionToolbarManager;
import android.view.selectiontoolbar.ShowInfo;

import com.android.internal.R;
import com.android.internal.infra.ServiceConnector;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.clipboard.ClipboardManagerInternal;
import com.android.server.input.InputManagerInternal;


public class SelectionToolbarManagerService extends SystemService {

    private static final String LOG_TAG = SelectionToolbarManagerService.class.getSimpleName();

    private final RemoteRenderServiceConnector mRemoteRenderServiceConnector;

    private InputManagerInternal mInputManagerInternal;
    private ClipboardManagerInternal mClipboardManagerInternal;


    public SelectionToolbarManagerService(Context context) {
        super(context);

        String serviceName = context.getResources()
                .getString(R.string.config_systemUiSelectionToolbarRenderService);
        final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
        mRemoteRenderServiceConnector = new RemoteRenderServiceConnector(context, serviceComponent,
                UserHandle.USER_SYSTEM, new SelectionToolbarRenderServiceRemoteCallback());
    }

    @Override
    public void onStart() {
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mClipboardManagerInternal = LocalServices.getService(ClipboardManagerInternal.class);

        publishBinderService(Context.SELECTION_TOOLBAR_SERVICE, new Stub());
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);

        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mRemoteRenderServiceConnector.connect(); // Prepare the binding in advance
        }
    }

    private class Stub extends ISelectionToolbarManager.Stub {

        @Override
        public void showToolbar(ShowInfo showInfo,
                ISelectionToolbarCallback iSelectionToolbarCallback) {
            mRemoteRenderServiceConnector
                    .showToolbar(Binder.getCallingUid(), showInfo, iSelectionToolbarCallback);
        }

        @Override
        public void hideToolbar() {
            mRemoteRenderServiceConnector.hideToolbar(Binder.getCallingUid());
        }

        @Override
        public void dismissToolbar() {
            mRemoteRenderServiceConnector.dismissToolbar(Binder.getCallingUid());
        }

    }

    private final class SelectionToolbarRenderServiceRemoteCallback extends
            ISelectionToolbarRenderServiceCallback.Stub {

        @Override
        public void transferTouch(IBinder source, IBinder target) {
            mInputManagerInternal.transferTouchGesture(source, target, false);
        }

        @Override
        public void onPasteAction(int uid, IBinder hostInputToken) {
            SecurePasteSelectionToolbar.onPasteAction(
                    mClipboardManagerInternal, uid, hostInputToken);
        }
    }

    private static class RemoteRenderServiceConnector extends
            ServiceConnector.Impl<ISelectionToolbarRenderService> {
        private final IBinder mCallback;

        private RemoteRenderServiceConnector(Context context, ComponentName serviceName,
                int userId, IBinder callback) {
            super(context, new Intent(SelectionToolbarRenderService.SERVICE_INTERFACE)
                            .setComponent(serviceName), 0, userId,
                    ISelectionToolbarRenderService.Stub::asInterface);
            mCallback = callback;
        }

        @Override
        protected long getAutoDisconnectTimeoutMs() {
            return 0; // Never unbind
        }

        @Override
        protected void onServiceConnectionStatusChanged(
                @NonNull ISelectionToolbarRenderService service, boolean isConnected) {
            try {
                if (isConnected) {
                    service.onConnected(mCallback);
                }
            } catch (Exception e) {
                Slog.w(LOG_TAG, "Exception calling onConnected().", e);
            }
        }

        private void showToolbar(int uid, ShowInfo showInfo,
                ISelectionToolbarCallback callback) {
            run(s -> s.onShow(uid, showInfo, callback));
        }

        private void hideToolbar(int uid) {
            run(s -> s.onHide(uid));
        }

        private void dismissToolbar(int uid) {
            run(s -> s.onDismiss(uid));
        }
    }
}
