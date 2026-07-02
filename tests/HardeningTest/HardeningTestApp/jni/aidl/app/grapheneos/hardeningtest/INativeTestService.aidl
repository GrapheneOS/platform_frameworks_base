package app.grapheneos.hardeningtest;

interface INativeTestService {
    int getPpid();
    int callFunc(String funcName, in @nullable ParcelFileDescriptor fd);
}
