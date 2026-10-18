#include "separator.hpp"
#include <jni.h>
#include <mutex>
namespace {
std::atomic_bool cancelled{false};
std::mutex operation;
std::string utf(JNIEnv *env, jstring value) {
    if (!value)
        throw std::runtime_error("Missing native file path");
    const char *p = env->GetStringUTFChars(value, nullptr);
    if (!p)
        throw std::runtime_error("Could not read native file path");
    std::string s(p);
    env->ReleaseStringUTFChars(value, p);
    return s;
}
void error(JNIEnv *env, const char *type, const char *message) {
    if (!env->ExceptionCheck()) {
        jclass cls = env->FindClass(type);
        if (cls)
            env->ThrowNew(cls, message);
    }
}
} // namespace
extern "C" JNIEXPORT void JNICALL Java_com_jhaago_vocalreducer_NativeSeparator_cancel(JNIEnv *,
                                                                                      jclass) {
    cancelled.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_com_jhaago_vocalreducer_NativeSeparator_separate(
    JNIEnv *env, jclass, jstring model, jstring source, jstring output, jlong frames,
    jobject callback) {
    std::unique_lock<std::mutex> lock(operation, std::try_to_lock);
    if (!lock.owns_lock()) {
        error(env, "java/lang/IllegalStateException", "A separation job is already running");
        return;
    }
    cancelled.store(false, std::memory_order_relaxed);
    try {
        auto m = utf(env, model), s = utf(env, source), o = utf(env, output);
        jmethodID method = nullptr;
        if (callback) {
            jclass type = env->GetObjectClass(callback);
            method = env->GetMethodID(type, "onProgress", "(FLjava/lang/String;)V");
            env->DeleteLocalRef(type);
            if (!method || env->ExceptionCheck())
                throw std::runtime_error("Progress callback is invalid");
        }
        auto backend = vocal::make_demucs_backend();
        vocal::separate_files(
            m, s, o, frames, cancelled,
            [&](float p, const std::string &stage) {
                if (callback) {
                    jstring text = env->NewStringUTF(stage.c_str());
                    if (!text)
                        throw std::runtime_error("Could not allocate progress text");
                    env->CallVoidMethod(callback, method, p, text);
                    env->DeleteLocalRef(text);
                    if (env->ExceptionCheck())
                        throw std::runtime_error("Progress callback failed");
                }
            },
            *backend);
    } catch (const vocal::Cancelled &e) {
        error(env, "java/util/concurrent/CancellationException", e.what());
    } catch (const std::exception &e) {
        error(env, "java/io/IOException", e.what());
    } catch (...) {
        error(env, "java/io/IOException", "Native separation failed");
    }
}
