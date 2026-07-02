package app.grapheneos.hardeningtest;

import android.os.ParcelFileDescriptor;

interface ITestService {
    @nullable String testDynamicCodeLoading(String type, boolean isAllowed, in ParcelFileDescriptor appDataFileFd, in ParcelFileDescriptor execmodFd, boolean callNativeService);
    @nullable String testPtrace(boolean isAllowed, int mainProcessPid, boolean callNativeService);
}
