// JNI bridge between the app (com.ilan.reminders.WhisperNative) and whisper.cpp.
// Runs fully on the phone: audio in, Hebrew text out. Nothing is sent anywhere.
#include <jni.h>
#include <atomic>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "rwhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

struct ProgressCtx {
    JNIEnv *env;
    jobject cb;
    jmethodID mid;
};

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *p = static_cast<ProgressCtx *>(user);
    if (p && p->cb && p->mid) p->env->CallVoidMethod(p->cb, p->mid, (jint) progress);
}

static bool on_abort(void *) { return g_abort.load(); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_ilan_reminders_WhisperNative_init(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ilan_reminders_WhisperNative_free(JNIEnv *, jobject, jlong ptr) {
    if (ptr) whisper_free((whisper_context *) ptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ilan_reminders_WhisperNative_cancel(JNIEnv *, jobject) { g_abort.store(true); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_ilan_reminders_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

/** Returns the text as UTF-8 bytes (decoded in Kotlin: safe for any character), or null when cancelled / failed.
 *  [cb] gets onProgress(int 0..100). */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ilan_reminders_WhisperNative_transcribe(JNIEnv *env, jobject, jlong ptr, jint threads,
                                                 jstring jlang, jfloatArray audio, jobject cb) {
    auto *ctx = (whisper_context *) ptr;
    if (!ctx) return nullptr;
    g_abort.store(false);

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = language.c_str();
    p.translate = false;
    p.no_context = true;
    p.single_segment = false;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;
    // short / quiet voice messages were dropped as "no speech": never discard a segment on the no-speech estimate
    p.no_speech_thold = 1.0f;
    p.logprob_thold = -3.0f;

    ProgressCtx pc{env, cb, nullptr};
    if (cb) {
        jclass cls = env->GetObjectClass(cb);
        pc.mid = env->GetMethodID(cls, "onProgress", "(I)V");
        p.progress_callback = on_progress;
        p.progress_callback_user_data = &pc;
    }
    p.abort_callback = on_abort;
    p.abort_callback_user_data = nullptr;

    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    if (rc != 0 || g_abort.load()) { LOGI("whisper_full rc=%d abort=%d", rc, (int) g_abort.load()); return nullptr; }

    std::string out;
    const int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (!t) continue;
        std::string s(t);
        // whisper starts most segments with a space; join segments with a single space
        size_t a = s.find_first_not_of(' ');
        if (a == std::string::npos) continue;
        if (!out.empty()) out += ' ';
        out += s.substr(a);
    }
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}
