package com.android.internal.gmscompat;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Context.BindServiceFlags;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.UserHandle;

import java.util.concurrent.Executor;

public interface IGmsCompatLib {
    void init(Context appContext, String processName);

    /** @see android.content.Context#bindService(Intent, BindServiceFlags, Executor, ServiceConnection) */
    @Nullable
    ServiceConnection maybeReplaceServiceConnection(Intent service, long flags, UserHandle user, ServiceConnection orig);
}
