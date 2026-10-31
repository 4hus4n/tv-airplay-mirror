/*
 * AirMirror for Windows - receiver for Android phones (AirMirror Sender app).
 *
 * Discovery: replies to UDP broadcast "AIRMIRROR_DISCOVER" on port 47101 with
 *            "AIRMIRROR|<name>|47100|pc".
 * Stream:    TCP 47100, packets [type:1][len:4 BE][ptsUs:8 BE][payload]
 *            1 = hello (phone name), 2 = H.264 Annex-B access unit,
 *            3 = AAC-LC raw frame (44.1 kHz stereo), 4 = keep-alive.
 * Playback:  GStreamer appsrc -> h264parse -> decodebin -> d3d11videosink (full screen)
 *            and appsrc -> avdec_aac -> autoaudiosink.
 *
 * Usage: amreceiver.exe -n "PC name"
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <gst/gst.h>
#include <gst/app/gstappsrc.h>

#define STREAM_PORT 47100
#define DISCOVERY_PORT 47101

static char g_name[256] = "Windows PC";
static GMainLoop *g_loop;
static volatile SOCKET g_client = INVALID_SOCKET;
static CRITICAL_SECTION g_lock;

typedef struct {
    GstElement *pipeline;
    GstAppSrc *video;
    GstAppSrc *audio;
    guint bus_watch;
} session_t;

static void logf_(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
    fputc('\n', stdout);
    fflush(stdout);
}

/* ------------------------------------------------------------------ pipeline */

static gboolean bus_cb(GstBus *bus, GstMessage *msg, gpointer data) {
    (void) bus; (void) data;
    if (GST_MESSAGE_TYPE(msg) == GST_MESSAGE_ERROR) {
        GError *err = NULL;
        gchar *dbg = NULL;
        gst_message_parse_error(msg, &err, &dbg);
        logf_("android: pipeline error from %s: %s", GST_OBJECT_NAME(msg->src), err ? err->message : "?");
        g_clear_error(&err);
        g_free(dbg);
        /* e.g. the user closed the video window: end this mirroring session */
        EnterCriticalSection(&g_lock);
        if (g_client != INVALID_SOCKET) shutdown(g_client, SD_BOTH);
        LeaveCriticalSection(&g_lock);
    }
    return TRUE;
}

static session_t *session_new(void) {
    const char *desc =
        "appsrc name=v is-live=true do-timestamp=true format=time "
        "  caps=video/x-h264,stream-format=byte-stream,alignment=au ! "
        "queue max-size-buffers=0 max-size-time=0 max-size-bytes=0 leaky=downstream ! "
        "h264parse ! decodebin ! videoconvert ! "
        "d3d11videosink name=sink sync=false fullscreen-toggle-mode=alt-enter fullscreen=true "
        "appsrc name=a is-live=true do-timestamp=true format=time "
        "  caps=audio/mpeg,mpegversion=4,stream-format=raw,channels=2,rate=44100,codec_data=(buffer)1210 ! "
        "queue ! avdec_aac ! audioconvert ! audioresample ! autoaudiosink sync=false";
    GError *err = NULL;
    GstElement *p = gst_parse_launch(desc, &err);
    if (!p) {
        logf_("android: could not build pipeline: %s", err ? err->message : "?");
        g_clear_error(&err);
        return NULL;
    }
    if (err) {   /* non-fatal warning (e.g. a property not supported) */
        logf_("android: pipeline warning: %s", err->message);
        g_clear_error(&err);
    }
    session_t *s = g_new0(session_t, 1);
    s->pipeline = p;
    s->video = GST_APP_SRC(gst_bin_get_by_name(GST_BIN(p), "v"));
    s->audio = GST_APP_SRC(gst_bin_get_by_name(GST_BIN(p), "a"));
    GstBus *bus = gst_element_get_bus(p);
    s->bus_watch = gst_bus_add_watch(bus, bus_cb, NULL);
    gst_object_unref(bus);
    gst_element_set_state(p, GST_STATE_PLAYING);
    return s;
}

static void session_free(session_t *s) {
    if (!s) return;
    gst_app_src_end_of_stream(s->video);
    gst_app_src_end_of_stream(s->audio);
    gst_element_set_state(s->pipeline, GST_STATE_NULL);
    if (s->bus_watch) g_source_remove(s->bus_watch);
    gst_object_unref(s->video);
    gst_object_unref(s->audio);
    gst_object_unref(s->pipeline);
    g_free(s);
}

static void push(GstAppSrc *src, uint8_t *data, uint32_t len) {
    GstBuffer *b = gst_buffer_new_wrapped(data, len);   /* takes ownership (g_malloc'd) */
    gst_app_src_push_buffer(src, b);
}

/* ------------------------------------------------------------------ network */

static int recv_all(SOCKET s, void *buf, int len) {
    char *p = (char *) buf;
    while (len > 0) {
        int n = recv(s, p, len, 0);
        if (n <= 0) return -1;
        p += n;
        len -= n;
    }
    return 0;
}

static void handle_client(SOCKET c) {
    DWORD timeout = 10000;
    setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, (const char *) &timeout, sizeof(timeout));
    int one = 1;
    setsockopt(c, IPPROTO_TCP, TCP_NODELAY, (const char *) &one, sizeof(one));

    session_t *s = NULL;
    uint8_t hdr[13];
    while (recv_all(c, hdr, sizeof(hdr)) == 0) {
        uint8_t type = hdr[0];
        uint32_t len = ((uint32_t) hdr[1] << 24) | ((uint32_t) hdr[2] << 16) | ((uint32_t) hdr[3] << 8) | hdr[4];
        if (len > 8u * 1024 * 1024) break;
        uint8_t *data = g_malloc(len ? len : 1);
        if (len && recv_all(c, data, (int) len) != 0) { g_free(data); break; }
        switch (type) {
        case 1: {
            char phone[256] = {0};
            memcpy(phone, data, len < 255 ? len : 255);
            logf_("android: %s connected", phone);
            g_free(data);
            if (!s) s = session_new();
            if (!s) goto done;
            break;
        }
        case 2:
            if (s && len) push(s->video, data, len); else g_free(data);
            break;
        case 3:
            if (s && len) push(s->audio, data, len); else g_free(data);
            break;
        default:
            g_free(data);
        }
    }
done:
    logf_("android: phone disconnected");
    session_free(s);
}

static DWORD WINAPI client_thread(LPVOID arg) {
    SOCKET c = (SOCKET) (uintptr_t) arg;
    handle_client(c);
    EnterCriticalSection(&g_lock);
    if (g_client == c) g_client = INVALID_SOCKET;
    LeaveCriticalSection(&g_lock);
    closesocket(c);
    return 0;
}

static DWORD WINAPI tcp_thread(LPVOID arg) {
    (void) arg;
    SOCKET ls = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    BOOL excl = TRUE;
    setsockopt(ls, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (const char *) &excl, sizeof(excl));
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET;
    a.sin_port = htons(STREAM_PORT);
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(ls, (struct sockaddr *) &a, sizeof(a)) != 0 || listen(ls, 2) != 0) {
        logf_("android: cannot listen on TCP %d (error %d)", STREAM_PORT, WSAGetLastError());
        return 1;
    }
    logf_("android: waiting for phones on TCP %d", STREAM_PORT);
    for (;;) {
        SOCKET c = accept(ls, NULL, NULL);
        if (c == INVALID_SOCKET) { Sleep(100); continue; }
        /* one phone at a time: a new phone replaces the old one */
        EnterCriticalSection(&g_lock);
        if (g_client != INVALID_SOCKET) shutdown(g_client, SD_BOTH);
        LeaveCriticalSection(&g_lock);
        for (int i = 0; i < 60 && g_client != INVALID_SOCKET; i++) Sleep(50);
        EnterCriticalSection(&g_lock);
        g_client = c;
        LeaveCriticalSection(&g_lock);
        HANDLE t = CreateThread(NULL, 0, client_thread, (LPVOID) (uintptr_t) c, 0, NULL);
        if (t) CloseHandle(t);
    }
    return 0;
}

static DWORD WINAPI udp_thread(LPVOID arg) {
    (void) arg;
    SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    BOOL on = TRUE;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char *) &on, sizeof(on));
    setsockopt(s, SOL_SOCKET, SO_BROADCAST, (const char *) &on, sizeof(on));
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET;
    a.sin_port = htons(DISCOVERY_PORT);
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(s, (struct sockaddr *) &a, sizeof(a)) != 0) {
        logf_("android: cannot listen on UDP %d (error %d)", DISCOVERY_PORT, WSAGetLastError());
        return 1;
    }
    char buf[512], reply[512];
    snprintf(reply, sizeof(reply), "AIRMIRROR|%s|%d|pc", g_name, STREAM_PORT);
    for (;;) {
        struct sockaddr_in from;
        int fl = sizeof(from);
        int n = recvfrom(s, buf, sizeof(buf) - 1, 0, (struct sockaddr *) &from, &fl);
        if (n <= 0) continue;
        buf[n] = 0;
        if (strncmp(buf, "AIRMIRROR_DISCOVER", 18) == 0) {
            sendto(s, reply, (int) strlen(reply), 0, (struct sockaddr *) &from, fl);
        }
    }
    return 0;
}

static void print_ips(void) {
    char host[256];
    if (gethostname(host, sizeof(host)) != 0) return;
    struct addrinfo hints = {0}, *res = NULL;
    hints.ai_family = AF_INET;
    if (getaddrinfo(host, NULL, &hints, &res) != 0) return;
    for (struct addrinfo *p = res; p; p = p->ai_next) {
        char ip[64];
        inet_ntop(AF_INET, &((struct sockaddr_in *) p->ai_addr)->sin_addr, ip, sizeof(ip));
        logf_("android: this PC's IP address: %s", ip);
    }
    freeaddrinfo(res);
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-n") && i + 1 < argc) {
            strncpy(g_name, argv[++i], sizeof(g_name) - 1);
        }
    }
    /* argv is in the ANSI code page; use the UTF-8 command line for the name */
    int wargc = 0;
    LPWSTR *wargv = CommandLineToArgvW(GetCommandLineW(), &wargc);
    if (wargv) {
        for (int i = 1; i + 1 < wargc; i++) {
            if (!wcscmp(wargv[i], L"-n")) {
                WideCharToMultiByte(CP_UTF8, 0, wargv[i + 1], -1, g_name, sizeof(g_name), NULL, NULL);
            }
        }
        LocalFree(wargv);
    }
    for (char *c = g_name; *c; c++) if (*c == '|') *c = ' ';

    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    InitializeCriticalSection(&g_lock);
    gst_init(&argc, &argv);
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--selftest")) {   /* CI: can the playback pipeline be built? */
            session_t *t = session_new();
            if (!t) return 1;
            g_usleep(500000);
            session_free(t);
            logf_("selftest ok");
            return 0;
        }
    }
    print_ips();

    CreateThread(NULL, 0, tcp_thread, NULL, 0, NULL);
    CreateThread(NULL, 0, udp_thread, NULL, 0, NULL);

    g_loop = g_main_loop_new(NULL, FALSE);
    g_main_loop_run(g_loop);
    return 0;
}
