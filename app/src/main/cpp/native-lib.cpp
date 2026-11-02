#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_yexclsv_localfiles_NativeBridge_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Native core 1.0 (C++)";
    return env->NewStringUTF(hello.c_str());
}
