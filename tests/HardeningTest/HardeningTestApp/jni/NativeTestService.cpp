#define LOG_TAG "NativeTestService"

#include <android/binder_ibinder.h>
#include <dlfcn.h>
#include <log/log.h>
#include <unistd.h>

#include "NativeTestService.h"

ndk::ScopedAStatus NativeTestService::getPpid(int32_t* ppid) {
    *ppid = getppid();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus NativeTestService::callFunc(const std::string& funcName, const ndk::ScopedFileDescriptor& fd, int32_t* res) {
    ALOGI("Calling function %s fd %i", funcName.c_str(), fd.get());
    void* handle = dlsym(RTLD_DEFAULT, funcName.c_str());
    if (handle == nullptr) {
        ALOGE("Failed to find function %s: %s", funcName.c_str(), dlerror());
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    if (fd.get() >= 0) {
        *res = reinterpret_cast<int (*)(void*, void*, int)>(handle)(nullptr, nullptr, fd.get());
    } else {
        *res = reinterpret_cast<int (*)(void*, void*)>(handle)(nullptr, nullptr);
    }
    return ndk::ScopedAStatus::ok();
}

// Code below was copied from frameworks/base/libs/native_activity_thread/tests/lib/SimpleNativeService.cpp

// This instance doesn't need to be guarded by locks because it's only accessed by ANativeService
// callbacks, which are executed on the main thread.
std::shared_ptr<NativeTestService> gService;

extern "C" AIBinder* onBind(ANativeService* _Nonnull /* service */, uint64_t /* bindToken */,
                            char const* _Nullable /* action */, char const* _Nullable /* data */) {
    ndk::SpAIBinder binder = gService->asBinder();
    AIBinder_incStrong(binder.get());
    return binder.get();
}

extern "C" void onDestroy(ANativeService* _Nonnull /* service */) {
    gService = nullptr;
}

extern "C" void ANativeService_onCreate(ANativeService* service) {
    gService = ndk::SharedRefBase::make<NativeTestService>();

    ANativeService_setOnBindCallback(service, onBind);
    ANativeService_setOnDestroyCallback(service, onDestroy);
}
