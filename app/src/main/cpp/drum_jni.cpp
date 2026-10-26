/**
 * AAudio fast output client for the drumpad (PROJECT.md §2 amendment, Phase 7).
 * Low-latency MMAP-capable stream: the device mixes our PCM on its own fast audio
 * thread via the data callback, dropping below the AudioTrack 2052-frame queue floor.
 * Same mixing math as the legacy Kotlin pull mixer, executed inside the callback.
 * No audio focus is ever requested; USAGE_MEDIA + CONTENT_TYPE_SONIFICATION.
 */
#include <jni.h>

#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <ctime>
#include <mutex>
#include <vector>

namespace {

constexpr const char* LOG_TAG = "OKDrumDrum";
constexpr int CALIBRATE_EVERY = 96;
constexpr size_t MAX_VOICES = 16;

struct Voice {
    // mono 16-bit PCM at the native rate; frames still to mix
    std::vector<int16_t> pcm;
    float gain = 1.0f;
    int64_t triggerNanos = 0;
    bool reported = false;
};

// Sized once at stream open; never allocated or resized on the audio thread.
struct MixContext {
    int32_t capacityFrames = 0;
    std::vector<int32_t> acc;
};

JavaVM* g_vm = nullptr;
jobject g_javaEngine = nullptr;  // global ref to the DrumEngine instance
jmethodID g_latencyMethod = nullptr;
AAudioStream* g_stream = nullptr;
int g_sampleRate = 48'000;
MixContext g_ctx;

std::mutex g_mutex;
std::vector<Voice> g_voices;
int64_t g_framesWritten = 0;
float g_lagMs = 0.0f;
int g_sinceCalibrate = CALIBRATE_EVERY;

int64_t nowNanos() {
    timespec ts = {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
}

void reportLatency(float totalMs, float appMs, float outputMs) {
    if (g_javaEngine == nullptr || g_latencyMethod == nullptr) return;
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    env->CallVoidMethod(g_javaEngine, g_latencyMethod, totalMs, appMs, outputMs);
}

// How far ahead of actual playback our writes sit; zeroes out the noise floor.
void calibratePlayheadLag(int64_t now) {
    int64_t framePos = 0;
    int64_t frameNanos = 0;
    if (AAudioStream_getTimestamp(g_stream, CLOCK_MONOTONIC, &framePos, &frameNanos) == AAUDIO_OK) {
        const int64_t expected = framePos + (now - frameNanos) * g_sampleRate / 1'000'000'000L;
        const float lagMs = static_cast<float>(g_framesWritten - expected) / g_sampleRate * 1000.0f;
        g_lagMs = lagMs > 0.0f ? lagMs : 0.0f;
    }
    g_sinceCalibrate = 0;
}

aaudio_data_callback_result_t dataCallback(AAudioStream*, void*, void* audioData, int32_t numFrames) {
    auto* out = static_cast<int16_t*>(audioData);
    MixContext& ctx = g_ctx;
    const int32_t usable = numFrames < ctx.capacityFrames ? numFrames : ctx.capacityFrames;
    const int64_t now = nowNanos();

    if (++g_sinceCalibrate >= CALIBRATE_EVERY) calibratePlayheadLag(now);

    std::fill(ctx.acc.begin(), ctx.acc.begin() + usable, 0);

    int64_t freshTriggerNanos = -1;
    {
        const std::lock_guard<std::mutex> lock(g_mutex);
        for (auto it = g_voices.begin(); it != g_voices.end();) {
            Voice& v = *it;
            const size_t copy = std::min<size_t>(static_cast<size_t>(usable), v.pcm.size());
            for (size_t i = 0; i < copy; ++i) {
                ctx.acc[i] += static_cast<int32_t>(static_cast<int32_t>(v.pcm[i]) * v.gain);
            }
            if (freshTriggerNanos < 0 && !v.reported) {
                freshTriggerNanos = v.triggerNanos;
                v.reported = true;
            }
            if (v.pcm.size() <= static_cast<size_t>(usable)) {
                it = g_voices.erase(it);
            } else {
                v.pcm.erase(v.pcm.begin(), v.pcm.begin() + usable);
                ++it;
            }
        }
        while (g_voices.size() > MAX_VOICES) g_voices.erase(g_voices.begin());
    }

    for (int32_t f = 0; f < usable; ++f) {
        // mono mix duplicated L/R; plain stereo satisfies the fast-track conformance
        auto sample = static_cast<int16_t>(std::clamp(ctx.acc[f], -32768, 32767));
        out[2 * f] = sample;
        out[2 * f + 1] = sample;
    }
    g_framesWritten += numFrames;

    if (freshTriggerNanos >= 0) {
        // app segment: trigger -> absorbed on this callback; output segment: playhead lag
        const float appMs = static_cast<float>(now - freshTriggerNanos) / 1'000'000.0f;
        reportLatency(appMs + g_lagMs, appMs, g_lagMs);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void errorCallback(AAudioStream*, void*, aaudio_result_t error) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "AAudio error: %s", AAudio_convertResultToText(error));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_okdrumdrum_app_engine_DrumEngine_nativeStart(JNIEnv* env, jobject thiz, jint sampleRate, jint capacityFrames) {
    if (g_javaEngine != nullptr) return AAUDIO_OK;  // already running
    g_sampleRate = static_cast<int>(sampleRate);
    g_javaEngine = env->NewGlobalRef(thiz);
    if (const jclass clazz = env->GetObjectClass(thiz)) {
        g_latencyMethod = env->GetMethodID(clazz, "onNativeLatency", "(FFF)V");
        env->DeleteLocalRef(clazz);
    }
    if (g_latencyMethod == nullptr) return AAUDIO_ERROR_INTERNAL;

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK) return res;
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setSampleRate(builder, g_sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    // Shrink the client FIFO: default capacity = 2048 frames ≈ 42.7 ms of written-ahead.
    // Two bursts (~10.7 ms on this device) is the minimum sane queue.
    if (capacityFrames > 0) AAudioStreamBuilder_setBufferCapacityInFrames(builder, static_cast<int32_t>(capacityFrames));
    // Experiment 3: GAME usage rides the output route WITHOUT the vendor music-effect
    // pipeline (dirac) that adds the deep constant on the loudspeaker path.
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, nullptr);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, nullptr);

    res = AAudioStreamBuilder_openStream(builder, &g_stream);
    if (res != AAUDIO_OK) {
        // EXCLUSIVE (MMAP) may be unavailable on some outputs — retry SHARED.
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        res = AAudioStreamBuilder_openStream(builder, &g_stream);
    }
    if (res != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return res;
    }
    AAudioStreamBuilder_delete(builder);

    g_ctx.capacityFrames = AAudioStream_getBufferCapacityInFrames(g_stream);
    g_ctx.acc.assign(g_ctx.capacityFrames, 0);

    res = AAudioStream_requestStart(g_stream);
    if (res != AAUDIO_OK) {
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return res;
    }
    __android_log_print(
        ANDROID_LOG_INFO, LOG_TAG, "aaudio ok: burst=%d cap=%d sharing=%d",
        AAudioStream_getFramesPerBurst(g_stream), g_ctx.capacityFrames,
        AAudioStream_getSharingMode(g_stream)
    );
    return AAUDIO_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_com_okdrumdrum_app_engine_DrumEngine_nativeTrigger(
    JNIEnv* env, jobject, jshortArray pcm, jfloat gain, jlong triggerNanos
) {
    const jsize length = env->GetArrayLength(pcm);
    if (length <= 0) return;
    Voice v;
    v.pcm.resize(static_cast<size_t>(length));
    env->GetShortArrayRegion(pcm, 0, length, v.pcm.data());
    v.gain = static_cast<float>(gain);
    v.triggerNanos = triggerNanos;
    const std::lock_guard<std::mutex> lock(g_mutex);
    g_voices.push_back(std::move(v));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_okdrumdrum_app_engine_DrumEngine_nativeFramesPerCallback(JNIEnv*, jobject) {
    if (g_stream == nullptr) return 0;
    if (__builtin_available(android 29, *)) {
        const int32_t frames = AAudioStream_getFramesPerDataCallback(g_stream);
        if (frames > 0) return frames;  // some drivers report 0; burst is the real period
    }
    return AAudioStream_getFramesPerBurst(g_stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_okdrumdrum_app_engine_DrumEngine_nativeStop(JNIEnv*, jobject) {
    if (g_stream != nullptr) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
    g_voices.clear();
    g_framesWritten = 0;
    g_lagMs = 0.0f;
    g_sinceCalibrate = CALIBRATE_EVERY;
    JNIEnv* env = nullptr;
    if (g_vm != nullptr && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK && g_javaEngine != nullptr) {
        env->DeleteGlobalRef(g_javaEngine);
        g_javaEngine = nullptr;
        g_latencyMethod = nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
