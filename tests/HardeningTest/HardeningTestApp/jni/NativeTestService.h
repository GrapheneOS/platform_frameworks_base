#pragma once

#include <aidl/app/grapheneos/hardeningtest/BnNativeTestService.h>
#include <android/binder_auto_utils.h>
#include <android/binder_status.h>
#include <android/native_service.h>

class NativeTestService : public aidl::app::grapheneos::hardeningtest::BnNativeTestService {
public:
    NativeTestService() = default;
    virtual ~NativeTestService() = default;

    ndk::ScopedAStatus getPpid(int32_t* ppid) override;
    ndk::ScopedAStatus callFunc(const std::string& funcName, const ndk::ScopedFileDescriptor& fd, int32_t* res) override;
};
