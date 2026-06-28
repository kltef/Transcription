// Thin JNI shim over whisper.cpp used by WhisperRefiner.kt.
// Loads a ggml model once and transcribes 16 kHz mono float utterances on demand.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "VoiceKbWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_kltef_voicekeyboard_asr_WhisperRefiner_nativeInit(
        JNIEnv *env, jclass /*clazz*/, jstring jModelPath, jint /*threads*/) {
    const char *path = env->GetStringUTFChars(jModelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU keeps it portable across all Android devices

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jModelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file failed");
        return 0;
    }
    LOGI("whisper model loaded");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kltef_voicekeyboard_asr_WhisperRefiner_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr, jfloatArray jSamples, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(jSamples);
    if (n <= 0) return env->NewStringUTF("");

    std::vector<float> samples(n);
    env->GetFloatArrayRegion(jSamples, 0, n, samples.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.language         = "en";
    params.n_threads        = threads > 0 ? threads : 4;
    params.suppress_blank   = true;

    if (whisper_full(ctx, params, samples.data(), n) != 0) {
        LOGE("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        out += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltef_voicekeyboard_asr_WhisperRefiner_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}
