#include <aidl/app/grapheneos/goscompat/securespawn/BnNativeSignalProbe.h>
#include <android/binder_ibinder.h>
#include <android/native_service.h>
#include <errno.h>
#include <signal.h>
#include <unistd.h>

#include <memory>
#include <vector>

namespace {

using aidl::app::grapheneos::goscompat::securespawn::BnNativeSignalProbe;

class NativeSignalProbe final : public BnNativeSignalProbe {
public:
    ndk::ScopedAStatus terminateWithSigterm() override {
        kill(getpid(), SIGTERM);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus getSupplementaryGroups(
            std::vector<int32_t>* _aidl_return) override {
        int groupCount = getgroups(0, nullptr);
        if (groupCount < 0) {
            return ndk::ScopedAStatus::fromServiceSpecificError(errno);
        }

        std::vector<gid_t> groups(static_cast<size_t>(groupCount));
        if (groupCount > 0) {
            groupCount = getgroups(groupCount, groups.data());
            if (groupCount < 0) {
                return ndk::ScopedAStatus::fromServiceSpecificError(errno);
            }
        }

        _aidl_return->resize(static_cast<size_t>(groupCount));
        for (int i = 0; i < groupCount; ++i) {
            (*_aidl_return)[i] = static_cast<int32_t>(groups[i]);
        }

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
