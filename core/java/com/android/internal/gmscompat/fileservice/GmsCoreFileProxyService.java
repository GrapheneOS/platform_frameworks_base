package com.android.internal.gmscompat.fileservice;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class GmsCoreFileProxyService extends Service {

    private FileProxyService service;

    @Override
    public void onCreate() {
        service = new FileProxyService(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return service.asBinder();
    }
}
