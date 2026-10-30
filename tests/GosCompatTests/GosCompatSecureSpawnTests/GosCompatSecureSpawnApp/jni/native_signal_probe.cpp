#include <aidl/app/grapheneos/goscompat/securespawn/BnNativeSignalProbe.h>
#include <android/binder_ibinder.h>
#include <android/native_service.h>
#include <signal.h>
#include <unistd.h>

#include <memory>

namespace {

using aidl::app::grapheneos::goscompat::securespawn::BnNativeSignalProbe;

class NativeSignalProbe final : public BnNativeSignalProbe {
public:
    ndk::ScopedAStatus terminateWithSigterm() override {
        kill(getpid(), SIGTERM);
        return ndk::ScopedAStatus::ok();
    }
};

std::shared_ptr<NativeSignalProbe> gService;

AIBinder* onBind(ANativeService*, uint64_t, const char*, const char*) {
    ndk::SpAIBinder binder = gService->asBinder();
    AIBinder_incStrong(binder.get());
    return binder.get();
}

void onDestroy(ANativeService*) {
    gService = nullptr;
}

}  // namespace

extern "C" void ANativeService_onCreate(ANativeService* service) {
    gService = ndk::SharedRefBase::make<NativeSignalProbe>();
    ANativeService_setOnBindCallback(service, onBind);
    ANativeService_setOnDestroyCallback(service, onDestroy);
}
