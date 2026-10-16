// JNI bridge between the UxPlay AirPlay library and the Android app.
// Video (H.264 Annex-B) and audio (AAC-ELD / AAC-LC) frames are handed
// to Kotlin, which decodes them with MediaCodec.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <atomic>
#include <string>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "logger.h"
}

#define TAG "AirMirrorNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;
static jobject g_server = nullptr;          // global ref to AirPlayServer instance
static jmethodID m_onVideoData, m_onVideoCodec, m_onVideoSize, m_onAudioFormat,
        m_onAudioData, m_onVolume, m_onConnections, m_onClient, m_onFlushVideo,
        m_onFlushAudio, m_onPin, m_onLog;

static raop_t *g_raop = nullptr;
static dnssd_t *g_dnssd = nullptr;
static std::atomic<int> g_open_connections{0};
static std::atomic<int> g_missed_feedback{0};
static std::atomic<bool> g_reset_requested{false};
static std::atomic<bool> g_running{false};
static pthread_t g_watch_thread;
static pthread_key_t g_detach_key;
static int64_t g_clock_offset = 0;
static std::string g_password;
static int g_pin_mode = 0;   // 0 = open, 3 = random 4-digit code shown on TV

// ---------- JNI env helpers ----------
static void detach_thread(void *) {
    if (g_vm) g_vm->DetachCurrentThread();
}

static JNIEnv *get_env() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    pthread_setspecific(g_detach_key, (void *) 1);
    return env;
}

static void call_void(jmethodID m, ...) {
    if (!g_server) return;
    JNIEnv *env = get_env();
    if (!env) return;
    va_list args;
    va_start(args, m);
    env->CallVoidMethodV(g_server, m, args);
    va_end(args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static void send_bytes(jmethodID m, const unsigned char *data, int len, jlong extra) {
    if (!g_server || len <= 0) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, len, (const jbyte *) data);
    env->CallVoidMethod(g_server, m, arr, extra);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static void send_string(jmethodID m, const char *s) {
    if (!g_server) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring js = env->NewStringUTF(s ? s : "");
    env->CallVoidMethod(g_server, m, js);
    env->DeleteLocalRef(js);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// ---------- RAOP callbacks ----------
extern "C" {

static void cb_log(void *cls, int level, const char *msg) {
    if (level <= LOGGER_ERR) __android_log_print(ANDROID_LOG_ERROR, "UxPlay", "%s", msg);
    else if (level <= LOGGER_INFO) __android_log_print(ANDROID_LOG_INFO, "UxPlay", "%s", msg);
    else __android_log_print(ANDROID_LOG_DEBUG, "UxPlay", "%s", msg);
}

static void cb_conn_init(void *cls) {
    int n = ++g_open_connections;
    call_void(m_onConnections, (jint) n);
}

static void cb_conn_destroy(void *cls) {
    int n = --g_open_connections;
    if (n < 0) { g_open_connections = 0; n = 0; }
    if (n == 0) g_clock_offset = 0;
    call_void(m_onConnections, (jint) n);
}

static void cb_conn_feedback(void *cls) {
    g_missed_feedback = 0;
}

static void cb_conn_reset(void *cls, int reason) {
    LOGI("conn_reset reason=%d", reason);
    g_reset_requested = true;
}

static void cb_video_reset(void *cls, reset_type_t type) {
    LOGI("video_reset type=%d", (int) type);
    call_void(m_onFlushVideo);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (!data || data->data_len <= 0) return;
    send_bytes(m_onAudioData, data->data, data->data_len, (jlong) data->ct);
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    if (!data || data->data_len <= 0) return;
    if (!g_clock_offset) {
        uint64_t local = data->ntp_time_local ? data->ntp_time_local : get_local_time();
        g_clock_offset = (int64_t) local - (int64_t) data->ntp_time_remote;
    }
    // ntp times are in nanoseconds; hand microseconds to MediaCodec
    jlong pts_us = (jlong) (((int64_t) data->ntp_time_remote + g_clock_offset) / 1000);
    send_bytes(m_onVideoData, data->data, data->data_len, pts_us);
}

static int cb_video_set_codec(void *cls, video_codec_t codec) {
    call_void(m_onVideoCodec, (jboolean) (codec == VIDEO_CODEC_H265));
    return 0;
}

static void cb_video_pause(void *cls) {}
static void cb_video_resume(void *cls) {}

static void cb_audio_flush(void *cls) { call_void(m_onFlushAudio); }
static void cb_video_flush(void *cls) { call_void(m_onFlushVideo); }

static double cb_audio_set_client_volume(void *cls) { return 0.0; }

static void cb_audio_set_volume(void *cls, float volume) {
    // AirPlay volume: -144 = mute, -30..0 dB slider
    float frac;
    if (volume <= -30.0f) frac = 0.0f;
    else if (volume >= 0.0f) frac = 1.0f;
    else frac = (30.0f + volume) / 30.0f;
    call_void(m_onVolume, (jfloat) frac);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    LOGI("audio format ct=%d spf=%d screen=%d", *ct, *spf, *usingScreen);
    call_void(m_onAudioFormat, (jint) *ct, (jint) *spf);
}

static void cb_video_report_size(void *cls, float *ws, float *hs, float *w, float *h) {
    call_void(m_onVideoSize, (jint) *ws, (jint) *hs);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    LOGI("client request: %s (%s) id=%s", name, model, deviceid);
    *admit = true;
    send_string(m_onClient, name);
}

static void cb_display_pin(void *cls, char *pin) {
    send_string(m_onPin, pin);
}

static const char *cb_passwd(void *cls, int *len) {
    if (g_pin_mode == 3) { *len = -1; return NULL; }   // random code shown on TV each time
    if (!g_password.empty()) { *len = (int) g_password.size(); return g_password.c_str(); }
    *len = 0;
    return NULL;
}

static void cb_noop_audio_buf(void *cls, const void *buffer, int buflen) {}
static void cb_noop(void *cls) {}
static void cb_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) {}
static void cb_remote(void *cls, const char *a, const char *b) {}
static void cb_register_client(void *cls, const char *id, const char *pk, const char *name) {}
static bool cb_check_register(void *cls, const char *pk) { return true; }
static void cb_export_dacp(void *cls, const char *a, const char *b) {}
static void cb_mirror_running(void *cls, bool running) {}
// HLS (YouTube-style "AirPlay video") is disabled; stubs only
static void cb_on_video_play(void *cls, const char *loc, const float pos) {}
static void cb_on_video_scrub(void *cls, const float pos) {}
static void cb_on_video_rate(void *cls, const float rate) {}
static void cb_on_video_stop(void *cls) {}
static void cb_on_video_info(void *cls, playback_info_t *info) {}
static float cb_on_playlist_remove(void *cls) { return 0.0f; }

} // extern "C"

// ---------- watchdog thread: lost-connection detection and server reset ----------
static void *watch_loop(void *) {
    int ticks = 0;
    while (g_running) {
        usleep(100 * 1000);
        if (++ticks >= 10) {               // once per second
            ticks = 0;
            if (g_open_connections > 0) {
                if (++g_missed_feedback > 15) {
                    LOGI("client silent for 15s: resetting");
                    g_reset_requested = true;
                }
            } else {
                g_missed_feedback = 0;
            }
        }
        if (g_reset_requested && g_raop) {
            g_reset_requested = false;
            g_missed_feedback = 0;
            raop_stop_httpd(g_raop);
            raop_remove_known_connections(g_raop);
            unsigned short port = raop_get_port(g_raop);
            raop_start_httpd(g_raop, &port);
            raop_set_port(g_raop, port);
            g_open_connections = 0;
            g_clock_offset = 0;
            call_void(m_onConnections, (jint) 0);
        }
    }
    return nullptr;
}

static int parse_mac(const char *mac, char out[6]) {
    unsigned int b[6];
    if (sscanf(mac, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) return -1;
    for (int i = 0; i < 6; i++) out[i] = (char) b[i];
    return 0;
}

static void stop_all() {
    g_running = false;
    if (g_watch_thread) {
        pthread_join(g_watch_thread, nullptr);
        g_watch_thread = 0;
    }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    g_open_connections = 0;
}

// ---------- JNI exports ----------
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    pthread_key_create(&g_detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ahusan_airmirror_AirPlayServer_nativeStart(JNIEnv *env, jobject thiz, jstring jname,
                                                    jstring jmac, jstring jkeyfile, jint width,
                                                    jint height, jint fps, jint pinMode,
                                                    jstring jpassword) {
    if (g_raop) stop_all();

    if (g_server) env->DeleteGlobalRef(g_server);
    g_server = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    m_onVideoData   = env->GetMethodID(cls, "onVideoData", "([BJ)V");
    m_onVideoCodec  = env->GetMethodID(cls, "onVideoCodec", "(Z)V");
    m_onVideoSize   = env->GetMethodID(cls, "onVideoSize", "(II)V");
    m_onAudioFormat = env->GetMethodID(cls, "onAudioFormat", "(II)V");
    m_onAudioData   = env->GetMethodID(cls, "onAudioData", "([BJ)V");
    m_onVolume      = env->GetMethodID(cls, "onVolume", "(F)V");
    m_onConnections = env->GetMethodID(cls, "onConnections", "(I)V");
    m_onClient      = env->GetMethodID(cls, "onClient", "(Ljava/lang/String;)V");
    m_onFlushVideo  = env->GetMethodID(cls, "onFlushVideo", "()V");
    m_onFlushAudio  = env->GetMethodID(cls, "onFlushAudio", "()V");
    m_onPin         = env->GetMethodID(cls, "onPin", "(Ljava/lang/String;)V");
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return -10; }

    const char *name = env->GetStringUTFChars(jname, nullptr);
    const char *mac = env->GetStringUTFChars(jmac, nullptr);
    const char *keyfile = env->GetStringUTFChars(jkeyfile, nullptr);
    const char *pw = env->GetStringUTFChars(jpassword, nullptr);
    std::string s_name(name), s_mac(mac), s_key(keyfile);
    g_password = pw;
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jmac, mac);
    env->ReleaseStringUTFChars(jkeyfile, keyfile);
    env->ReleaseStringUTFChars(jpassword, pw);

    g_pin_mode = pinMode;
    unsigned char pin_pw = 0;
    if (pinMode == 3) pin_pw = 3;
    else if (!g_password.empty()) pin_pw = 2;

    char hw[6];
    if (parse_mac(s_mac.c_str(), hw)) return -1;

    ntp_global_init();

    int err = 0;
    g_dnssd = dnssd_init(s_name.c_str(), (int) s_name.size(), hw, 6, pin_pw, &err);
    if (err || !g_dnssd) { LOGE("dnssd_init failed %d", err); return -2; }

    // feature bits as in UxPlay defaults (mirroring + audio, no HLS video)
    dnssd_set_airplay_features(g_dnssd, 0, 0);
    dnssd_set_airplay_features(g_dnssd, 4, 0);
    dnssd_set_airplay_features(g_dnssd, 7, 1);
    dnssd_set_airplay_features(g_dnssd, 9, 1);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.video_reset = cb_video_reset;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.audio_set_metadata = cb_noop_audio_buf;
    cbs.audio_set_coverart = cb_noop_audio_buf;
    cbs.audio_stop_coverart_rendering = cb_noop;
    cbs.audio_set_progress = cb_progress;
    cbs.audio_remote_control_id = cb_remote;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.mirror_video_running = cb_mirror_running;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_info;
    cbs.on_video_playlist_remove = cb_on_playlist_remove;

    g_raop = raop_init(&cbs);
    if (!g_raop) { stop_all(); return -3; }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1, s_mac.c_str(), s_key.c_str())) {
        free(g_raop);
        g_raop = nullptr;
        stop_all();
        return -4;
    }
    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", fps);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) { stop_all(); return -5; }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);

    if (dnssd_register_raop(g_dnssd, port)) { stop_all(); return -6; }
    if (dnssd_register_airplay(g_dnssd, port)) { stop_all(); return -7; }

    g_running = true;
    pthread_create(&g_watch_thread, nullptr, watch_loop, nullptr);
    LOGI("AirPlay receiver '%s' running on port %d", s_name.c_str(), port);
    return port;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ahusan_airmirror_AirPlayServer_nativeStop(JNIEnv *env, jobject thiz) {
    stop_all();
    if (g_server) {
        env->DeleteGlobalRef(g_server);
        g_server = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ahusan_airmirror_AirPlayServer_nativeDisconnect(JNIEnv *env, jobject thiz) {
    g_reset_requested = true;
}
