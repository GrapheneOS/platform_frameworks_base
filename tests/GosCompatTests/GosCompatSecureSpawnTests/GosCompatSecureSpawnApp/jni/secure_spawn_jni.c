#include <jni.h>
#include <sys/prctl.h>
#include <sys/system_properties.h>

JNIEXPORT jstring JNICALL
Java_app_grapheneos_goscompat_securespawn_SecureSpawnCheck_nativeSystemProperty(
        JNIEnv* env, jclass clazz, jstring key) {
    (void) clazz;

    const char* key_chars = (*env)->GetStringUTFChars(env, key, NULL);
    if (key_chars == NULL) {
        return NULL;
    }

    char value[PROP_VALUE_MAX] = "";
    __system_property_get(key_chars, value);
    (*env)->ReleaseStringUTFChars(env, key, key_chars);
    return (*env)->NewStringUTF(env, value);
}

JNIEXPORT jint JNICALL
Java_app_grapheneos_goscompat_securespawn_SecureSpawnCheck_nativeDumpable(
        JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;

    return prctl(PR_GET_DUMPABLE);
}
