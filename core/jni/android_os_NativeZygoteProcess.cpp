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

#define ATRACE_TAG ATRACE_TAG_ACTIVITY_MANAGER

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <binder/IInterface.h>
#include <cutils/sockets.h>
#include <flatbuffers/flatbuffers.h>
#include <libzygote_messages_schemas/messages.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <utils/Trace.h>

#include <cstring>

#include "android_util_Binder.h"
#include "com_android_internal_os_Zygote.h"
#include "core_jni_helpers.h"

// Should be in sync with MESSAGE_BUFFER_SIZE in system/zygote/zygote-messages/src/lib.rs
#define RESPONSE_DATA_BUF_SIZE 2048

constexpr char NATIVE_ZYGOTE_INIT_SERVICE_NAME[] = "zygote_next";
constexpr int NATIVE_ZYGOTE_STARTUP_TIMEOUT_IN_MILLIS = 20000;

constexpr char PROP_INIT_START_SERVICE[] = "ctl.start";
constexpr char PROP_ZYGOTE_NEXT_READY[] = "zygote.zygote_next.server_ready";
constexpr char PROP_ZYGOTE_NEXT_START_ON_BOOT[] = "persist.zygote.zygote_next.start_on_boot";

constexpr char PROP_VALUE_TRUE[] = "true";

namespace {

static std::optional<ScopedUtfChars> extract_jstring(JNIEnv* env, jstring managed_string) {
    if (managed_string == nullptr) {
        return std::nullopt;
    }
    return ScopedUtfChars(env, managed_string);
}

static void CreateSpawnParcel(flatbuffers::FlatBufferBuilder& builder, JNIEnv* env, jlong selinux_flags, jint uid,
                              jint gid, const char* niceNameStr, bool is_child_zygote,
                              const char* seInfoStr, const char* socketPathStr,
                              SpawnPayload payload_type, flatbuffers::Offset<void> payload) {
    int32_t priority_initial = -20;
    int32_t priority_final = 0;
    jintArray gids = nullptr;
    jlong capabilities =
            android::zygote::CalculateCapabilities(env, uid, gid, gids, is_child_zygote);
    jlong bounding_capabilities =
            android::zygote::CalculateBoundingCapabilities(env, uid, gid, gids);
    uint64_t cap_effective = capabilities;
    uint64_t cap_permitted = capabilities;
    uint64_t cap_bound = bounding_capabilities;
    uint64_t cap_inheritable = cap_permitted | cap_bound;
    std::vector<uint32_t> secondary_groups;
    std::vector<RLimitData> rlimits;
    auto spawnCommon =
            CreateSpawnCommonDirect(builder, selinux_flags, uid, gid, niceNameStr, priority_initial,
                                    priority_final, cap_effective, cap_permitted, cap_inheritable,
                                    cap_bound, seInfoStr, &secondary_groups, &rlimits);
    flatbuffers::Offset<void> spawnUnion;
    Message message_type;
    if (payload_type == SpawnPayload_SpawnSubspeciesAndroidNative) {
        spawnUnion = CreateSpawnSubspeciesDirect(builder, spawnCommon, socketPathStr, payload_type,
                                                 payload)
                             .Union();
        message_type = Message_SpawnSubspecies;
    } else {
        spawnUnion = CreateSpawn(builder, spawnCommon, payload_type, payload).Union();
        message_type = Message_Spawn;
    }
    auto parcel = CreateParcel(builder, message_type, spawnUnion);
    builder.Finish(parcel);
}

static jint ReadSpawnResponse(int fd, JNIEnv* env) {
    uint8_t response_buf[RESPONSE_DATA_BUF_SIZE];
    memset(response_buf, 0, sizeof(response_buf));
    int received = read(fd, response_buf, sizeof(response_buf));
    if (received == -1) {
        jniThrowIOException(env, errno);
        return -1;
    }
    flatbuffers::Verifier verifier(response_buf, received);
    if (!VerifyParcelBuffer(verifier)) {
        jniThrowRuntimeException(env, "Failed to verify the response");
        return -1;
    }
    const auto* response = GetParcel(response_buf);
    switch (response->message_type()) {
        case Message_SpawnResponse: {
            const SpawnResponse* spawn_res = response->message_as_SpawnResponse();
            return spawn_res->pid();
        }
        default:
            jniThrowRuntimeException(env, "Received an unexpected type response");
            return -1;
    }
}

bool isNativeZygoteReady() {
    return android::base::GetBoolProperty(PROP_ZYGOTE_NEXT_READY, false);
}

void startNativeZygote() {
    android::base::SetProperty(PROP_INIT_START_SERVICE, NATIVE_ZYGOTE_INIT_SERVICE_NAME);
}

bool waitUntilNativeZygoteReady() {
    std::chrono::milliseconds timeout{NATIVE_ZYGOTE_STARTUP_TIMEOUT_IN_MILLIS};
    return android::base::WaitForProperty(PROP_ZYGOTE_NEXT_READY, PROP_VALUE_TRUE, timeout);
}

} // namespace

namespace android {

static jint captureCommand(JNIEnv* env, uint8_t* buf, ssize_t size, jobjectArray outCapturedCommand) {
    if (size >= INT32_MAX) {
        jniThrowIOException(env, E2BIG);
        return -1;
    }
    jbyteArray out = env->NewByteArray((jint) size);
    if (out == nullptr) {
        return -1;
    }
    env->SetByteArrayRegion(out, 0, (jint) size, (jbyte*) buf);
    env->SetObjectArrayElement(outCapturedCommand, 0, out);
    return 0;
}

static jint android_os_NativeZygoteProcess_startNativeProcess(
        JNIEnv* env, jclass /* classObj */,
        jobjectArray outCapturedCommand, jlong selinuxFlags, jbyteArray preloadCommand,
        jobject sockFd, jint uid, jint gid, jlong startSeq,
        jstring packageName, jstring niceName, jint targetSdkVersion, jboolean startChildZygote,
        jint runtimeFlags, jstring seInfo, jboolean isTopApp) {
    std::vector<uint8_t> preloadCommandVec;
    if (preloadCommand != nullptr) {
        ScopedByteArrayRO sba(env, preloadCommand);
        preloadCommandVec.insert(preloadCommandVec.end(), sba.get(), sba.get() + sba.size());
    }
    auto packageNameStr = extract_jstring(env, packageName);
    auto niceNameStr = extract_jstring(env, niceName);
    auto seInfoStr = extract_jstring(env, seInfo);
    const char* packageNamePtr = packageNameStr ? packageNameStr.value().c_str() : nullptr;
    const char* niceNamePtr = niceNameStr ? niceNameStr.value().c_str() : nullptr;
    const char* seInfoPtr = seInfoStr ? seInfoStr.value().c_str() : nullptr;
    bool is_top_app = isTopApp == JNI_TRUE;

    flatbuffers::FlatBufferBuilder builder;
    auto spawnAndroidNativeCmd =
            CreateSpawnAndroidNativeDirect(builder, &preloadCommandVec, packageNamePtr, startSeq, targetSdkVersion,
                                           static_cast<unsigned>(runtimeFlags), is_top_app);
    bool is_child_zygote = startChildZygote == JNI_TRUE;
    CreateSpawnParcel(builder, env, selinuxFlags, uid, gid, niceNamePtr, is_child_zygote, seInfoPtr,
                      /**subspecies_data=*/nullptr, SpawnPayload_SpawnAndroidNative,
                      spawnAndroidNativeCmd.Union());
    uint8_t* buf = builder.GetBufferPointer();
    ssize_t size = builder.GetSize();

    if (outCapturedCommand != nullptr) {
        return captureCommand(env, buf, size, outCapturedCommand);
    }

    int fd = jniGetFDFromFileDescriptor(env, sockFd);
    if (fd < 0) {
        jniThrowRuntimeException(env, "Failed to get a valid file descriptor");
        return -1;
    }
    ssize_t written = write(fd, buf, size);
    if (written == -1 || written != size) {
        jniThrowIOException(env, errno);
        return -1;
    }

    return ReadSpawnResponse(fd, env);
}

static jint android_os_NativeZygoteProcess_startNativeChildZygote(
        JNIEnv* env, jclass /* classObj */, jobjectArray outCapturedCommand, jlong selinuxFlags, jobject sockFd, jint uid, jint gid, jstring niceName,
        jstring seInfo, jint targetSdkVersion, jint runtimeFlags, jstring serverPath,
        jint uidRangeStart, jint uidRangeEnd, jstring allowedLibPath, jstring librarySearchPaths,
        jboolean isShared, jstring zipPath, jstring nativeSharedLibPath, jstring libraryPath,
        jstring preloadFunc) {
    auto niceNameChars = extract_jstring(env, niceName);
    auto seInfoChars = extract_jstring(env, seInfo);
    auto libraryPathChars = extract_jstring(env, libraryPath);
    auto allowedLibPathChars = extract_jstring(env, allowedLibPath);
    auto librarySearchChars = extract_jstring(env, librarySearchPaths);
    auto preloadFuncChars = extract_jstring(env, preloadFunc);
    auto serverPathName = extract_jstring(env, serverPath);
    auto zipPathChars = extract_jstring(env, zipPath);
    auto nativeSharedLibPathChars = extract_jstring(env, nativeSharedLibPath);

    const char* niceNameStr = niceNameChars ? niceNameChars->c_str() : nullptr;
    const char* seInfoStr = seInfoChars ? seInfoChars->c_str() : nullptr;
    const char* libraryPathStr = libraryPathChars ? libraryPathChars->c_str() : nullptr;
    const char* allowedLibPathStr = allowedLibPathChars ? allowedLibPathChars->c_str() : nullptr;
    const char* librarySearchStr = librarySearchChars ? librarySearchChars->c_str() : nullptr;
    const char* preloadFuncStr = preloadFuncChars ? preloadFuncChars->c_str() : nullptr;
    const char* serverPathStr = serverPathName ? serverPathName->c_str() : nullptr;
    const char* zipPathStr = zipPathChars ? zipPathChars->c_str() : nullptr;
    const char* nativeSharedLibPathStr =
            nativeSharedLibPathChars ? nativeSharedLibPathChars->c_str() : nullptr;

    flatbuffers::FlatBufferBuilder builder;

    auto spawnAndroidChildZygoteCmd =
            CreateSpawnSubspeciesAndroidNativeDirect(builder, targetSdkVersion,
                                                     static_cast<unsigned>(runtimeFlags),
                                                     libraryPathStr, librarySearchStr,
                                                     allowedLibPathStr, isShared == JNI_TRUE,
                                                     zipPathStr, nativeSharedLibPathStr,
                                                     preloadFuncStr, uidRangeStart, uidRangeEnd);

    if (outCapturedCommand != nullptr) {
        builder.Finish(spawnAndroidChildZygoteCmd);
        return captureCommand(env, builder.GetBufferPointer(), builder.GetSize(), outCapturedCommand);
    }

    CreateSpawnParcel(builder, env, selinuxFlags, uid, gid, niceNameStr, /**is_child_zygote=*/true, seInfoStr,
                      serverPathStr, SpawnPayload_SpawnSubspeciesAndroidNative,
                      spawnAndroidChildZygoteCmd.Union());

    uint8_t* buf = builder.GetBufferPointer();
    ssize_t size = builder.GetSize();

    int fd = jniGetFDFromFileDescriptor(env, sockFd);
    if (fd < 0) {
        jniThrowRuntimeException(env, "Failed to get a valid file descriptor");
        return -1;
    }

    ssize_t written = write(fd, buf, size);
    if (written == -1 || written != size) {
        jniThrowIOException(env, errno);
        return -1;
    }

    return ReadSpawnResponse(fd, env);
}

static jboolean android_os_NativeZygoteProcess_ensureNativeZygoteReadyBlocking(
        JNIEnv* env, jclass /* classObj */) {
    ATRACE_NAME("ensureNativeZygoteReadyBlocking");
    jboolean res = JNI_TRUE;
    if (!isNativeZygoteReady()) {
        startNativeZygote();
        res = waitUntilNativeZygoteReady() ? JNI_TRUE : JNI_FALSE;
    }
    return res;
}

static void android_os_NativeZygoteProcess_prewarmNativeZygote(JNIEnv* env, jclass /* classObj */) {
    if (isNativeZygoteReady()) return;
    startNativeZygote();

    // It's likely the first time to use a native service on this device.
    // Update the prop to start the native zygote on boot from next time,
    // assuming that native services continue to be used. This is a simplified
    // assumption and may not always be true, but enough for a temporary solution to
    // mitigate impact by running the native zygote unnecessarily.
    // TODO: b/458223100 - Remove this logic when zygote_next is used in all
    // form-factors.
    android::base::SetProperty(PROP_ZYGOTE_NEXT_START_ON_BOOT, PROP_VALUE_TRUE);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod method_table[] = {
        /* name, signature, funcPtr */
        {"nativeStartNativeProcess",
         "([[BJ[BLjava/io/FileDescriptor;IIJLjava/lang/String;Ljava/lang/String;IZILjava/lang/String;Z)I",
         (void*)android_os_NativeZygoteProcess_startNativeProcess},
        {"nativeStartNativeChildZygote",
         "([[BJLjava/io/FileDescriptor;IILjava/lang/String;Ljava/lang/String;II"
         "Ljava/lang/String;IILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/"
         "String;Ljava/lang/String;"
         "Ljava/lang/String;)I",
         (void*)android_os_NativeZygoteProcess_startNativeChildZygote},
        {"nativeEnsureNativeZygoteReadyBlocking", "()Z",
         (void*)android_os_NativeZygoteProcess_ensureNativeZygoteReadyBlocking},
        {"nativePrewarmNativeZygote", "()V",
         (void*)android_os_NativeZygoteProcess_prewarmNativeZygote},
};

int register_android_os_NativeZygoteProcess(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/os/NativeZygoteProcess", method_table,
                                NELEM(method_table));
}

}; // namespace android
