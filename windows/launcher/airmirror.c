/*
 * AirMirror for Windows - tray launcher.
 *
 * Runs the bundled AirPlay receiver (UxPlay, bin\uxplay.exe) in the background
 * with the bundled GStreamer, shows a tray icon, restarts the receiver if it
 * stops, and offers: Change name, Start with Windows, Show log, Restart, Quit.
 */
#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <wchar.h>
#include <stdio.h>

#define WM_TRAY          (WM_APP + 1)
#define WM_CHILD_EXITED  (WM_APP + 2)
#define ID_TRAY          1
#define IDM_STATUS       100
#define IDM_RENAME       101
#define IDM_AUTOSTART    102
#define IDM_LOG          103
#define IDM_RESTART      104
#define IDM_QUIT         105
#define IDM_FIREWALL     106

static const wchar_t *APP = L"AirMirror";
static const wchar_t *RUN_KEY = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";

static HWND g_hwnd;
static NOTIFYICONDATAW g_nid;
static HANDLE g_job;
static HANDLE g_child;
static HANDLE g_watch_thread;
static volatile LONG g_quitting = 0;
static wchar_t g_dir[MAX_PATH];        /* install dir (folder of this exe) */
static wchar_t g_data[MAX_PATH];       /* %LOCALAPPDATA%\AirMirror */
static wchar_t g_name[128];
static int g_failures = 0;
static DWORD g_last_start = 0;

/* ------------------------------------------------------------ helpers */

static void path_join(wchar_t *out, size_t n, const wchar_t *a, const wchar_t *b) {
    _snwprintf(out, n, L"%s\\%s", a, b);
    out[n - 1] = 0;
}

static void trim(wchar_t *s) {
    size_t n = wcslen(s);
    while (n && (s[n - 1] == L'\r' || s[n - 1] == L'\n' || s[n - 1] == L' ' || s[n - 1] == L'\t')) s[--n] = 0;
    size_t i = 0;
    while (s[i] == L' ' || s[i] == L'\t' || s[i] == 0xFEFF) i++;
    if (i) memmove(s, s + i, (wcslen(s + i) + 1) * sizeof(wchar_t));
}

/* name file holds the AirPlay name as UTF-8 text */
static void load_name(void) {
    wchar_t file[MAX_PATH];
    path_join(file, MAX_PATH, g_data, L"name.txt");
    g_name[0] = 0;
    FILE *f = _wfopen(file, L"rb");
    if (f) {
        char buf[256] = {0};
        size_t r = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        buf[r] = 0;
        char *p = buf;
        if ((unsigned char) p[0] == 0xEF && (unsigned char) p[1] == 0xBB && (unsigned char) p[2] == 0xBF) p += 3;
        MultiByteToWideChar(CP_UTF8, 0, p, -1, g_name, 127);
        trim(g_name);
    }
    if (!g_name[0]) {
        DWORD n = 127;
        if (!GetComputerNameW(g_name, &n) || !g_name[0]) wcscpy(g_name, L"Windows PC");
        /* write default so the user can edit it */
        f = _wfopen(file, L"wb");
        if (f) {
            char u[512];
            WideCharToMultiByte(CP_UTF8, 0, g_name, -1, u, sizeof(u), NULL, NULL);
            fputs(u, f);
            fputs("\r\n", f);
            fclose(f);
        }
    }
    /* keep only characters that are safe in an mDNS service name */
    for (wchar_t *c = g_name; *c; c++) {
        if (*c == L'"' || *c == L'\\' || *c == L'.' || *c == L'@' || *c < 32) *c = L' ';
    }
    trim(g_name);
    if (!g_name[0]) wcscpy(g_name, L"Windows PC");
}

static BOOL autostart_enabled(void) {
    HKEY k;
    BOOL on = FALSE;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, KEY_READ, &k) == ERROR_SUCCESS) {
        on = RegQueryValueExW(k, APP, NULL, NULL, NULL, NULL) == ERROR_SUCCESS;
        RegCloseKey(k);
    }
    return on;
}

static void set_autostart(BOOL on) {
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, KEY_WRITE, &k) != ERROR_SUCCESS) return;
    if (on) {
        wchar_t exe[MAX_PATH], val[MAX_PATH + 16];
        GetModuleFileNameW(NULL, exe, MAX_PATH);
        _snwprintf(val, MAX_PATH + 16, L"\"%s\" --tray", exe);
        RegSetValueExW(k, APP, 0, REG_SZ, (const BYTE *) val, (DWORD) ((wcslen(val) + 1) * sizeof(wchar_t)));
    } else {
        RegDeleteValueW(k, APP);
    }
    RegCloseKey(k);
}

static void balloon(const wchar_t *title, const wchar_t *text) {
    g_nid.uFlags = NIF_INFO;
    wcsncpy(g_nid.szInfoTitle, title, 63);
    g_nid.szInfoTitle[63] = 0;
    wcsncpy(g_nid.szInfo, text, 255);
    g_nid.szInfo[255] = 0;
    g_nid.dwInfoFlags = NIIF_INFO;
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
}

static void set_tip(const wchar_t *tip) {
    g_nid.uFlags = NIF_TIP;
    wcsncpy(g_nid.szTip, tip, 127);
    g_nid.szTip[127] = 0;
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
}

/* ------------------------------------------------------------ receiver process */

static DWORD WINAPI watch_child(LPVOID p) {
    HANDLE h = (HANDLE) p;
    WaitForSingleObject(h, INFINITE);
    if (!g_quitting) PostMessageW(g_hwnd, WM_CHILD_EXITED, 0, 0);
    return 0;
}

static void stop_receiver(void) {
    if (g_child) {
        TerminateProcess(g_child, 0);
        WaitForSingleObject(g_child, 3000);
        CloseHandle(g_child);
        g_child = NULL;
    }
    if (g_watch_thread) {
        WaitForSingleObject(g_watch_thread, 1000);
        CloseHandle(g_watch_thread);
        g_watch_thread = NULL;
    }
}

static BOOL start_receiver(void) {
    wchar_t bin[MAX_PATH], exe[MAX_PATH], plugins[MAX_PATH], scanner[MAX_PATH],
            registry[MAX_PATH], logfile[MAX_PATH], keyfile[MAX_PATH];
    path_join(bin, MAX_PATH, g_dir, L"bin");
    path_join(exe, MAX_PATH, bin, L"uxplay.exe");
    path_join(plugins, MAX_PATH, g_dir, L"lib\\gstreamer-1.0");
    path_join(scanner, MAX_PATH, g_dir, L"libexec\\gstreamer-1.0\\gst-plugin-scanner.exe");
    path_join(registry, MAX_PATH, g_data, L"gst-registry.bin");
    path_join(logfile, MAX_PATH, g_data, L"log.txt");
    path_join(keyfile, MAX_PATH, g_data, L"airplay.pem");

    if (GetFileAttributesW(exe) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(NULL, L"bin\\uxplay.exe is missing. Please reinstall AirMirror.", APP, MB_ICONERROR);
        return FALSE;
    }

    /* environment: bundled GStreamer only, never a system-wide one */
    static wchar_t path_env[32768];
    _snwprintf(path_env, 32768, L"%s;%s\\System32;%s", bin, _wgetenv(L"SystemRoot") ? _wgetenv(L"SystemRoot") : L"C:\\Windows",
               _wgetenv(L"SystemRoot") ? _wgetenv(L"SystemRoot") : L"C:\\Windows");
    SetEnvironmentVariableW(L"PATH", path_env);
    SetEnvironmentVariableW(L"GST_PLUGIN_SYSTEM_PATH_1_0", plugins);
    SetEnvironmentVariableW(L"GST_PLUGIN_SYSTEM_PATH", plugins);
    SetEnvironmentVariableW(L"GST_PLUGIN_PATH_1_0", NULL);
    SetEnvironmentVariableW(L"GST_PLUGIN_PATH", NULL);
    SetEnvironmentVariableW(L"GST_PLUGIN_SCANNER_1_0", scanner);
    SetEnvironmentVariableW(L"GST_PLUGIN_SCANNER", scanner);
    SetEnvironmentVariableW(L"GST_REGISTRY_1_0", registry);
    SetEnvironmentVariableW(L"GST_REGISTRY", registry);
    SetEnvironmentVariableW(L"HOME", g_data);   /* uxplay looks for ~/.uxplayrc */

    /* full screen D3D11 window, receiver name, remembered pairing key */
    static wchar_t cmd[2048];
    _snwprintf(cmd, 2048,
               L"\"%s\" -n \"%s\" -nh -vs d3d11videosink -fs -nofreeze -key \"%s\"",
               exe, g_name, keyfile);

    SECURITY_ATTRIBUTES sa = { sizeof(sa), NULL, TRUE };
    HANDLE log = CreateFileW(logfile, GENERIC_WRITE, FILE_SHARE_READ, &sa, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    if (log != INVALID_HANDLE_VALUE) {
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdOutput = log;
        si.hStdError = log;
        si.hStdInput = NULL;
    }
    ZeroMemory(&pi, sizeof(pi));
    BOOL ok = CreateProcessW(NULL, cmd, NULL, NULL, TRUE,
                             CREATE_NO_WINDOW | CREATE_SUSPENDED, NULL, bin, &si, &pi);
    if (log != INVALID_HANDLE_VALUE) CloseHandle(log);
    if (!ok) {
        MessageBoxW(NULL, L"Could not start the AirPlay receiver.", APP, MB_ICONERROR);
        return FALSE;
    }
    if (g_job) AssignProcessToJobObject(g_job, pi.hProcess);
    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    g_child = pi.hProcess;
    g_last_start = GetTickCount();
    g_watch_thread = CreateThread(NULL, 0, watch_child, g_child, 0, NULL);

    wchar_t tip[128];
    _snwprintf(tip, 128, L"AirMirror - \"%s\" is ready", g_name);
    set_tip(tip);
    return TRUE;
}

static void restart_receiver(void) {
    stop_receiver();
    load_name();
    start_receiver();
}

/* ------------------------------------------------------------ menu actions */

static void open_log(void) {
    wchar_t logfile[MAX_PATH];
    path_join(logfile, MAX_PATH, g_data, L"log.txt");
    ShellExecuteW(NULL, L"open", L"notepad.exe", logfile, NULL, SW_SHOWNORMAL);
}

static void rename_receiver(void) {
    wchar_t file[MAX_PATH];
    path_join(file, MAX_PATH, g_data, L"name.txt");
    MessageBoxW(NULL,
                L"Notepad will open with the name your iPhone sees.\n\n"
                L"Type the new name, save (Ctrl+S) and close Notepad.\nAirMirror restarts with the new name.",
                APP, MB_ICONINFORMATION);
    SHELLEXECUTEINFOW sei = { sizeof(sei) };
    sei.fMask = SEE_MASK_NOCLOSEPROCESS;
    sei.lpVerb = L"open";
    sei.lpFile = L"notepad.exe";
    sei.lpParameters = file;
    sei.nShow = SW_SHOWNORMAL;
    if (ShellExecuteExW(&sei) && sei.hProcess) {
        WaitForSingleObject(sei.hProcess, INFINITE);
        CloseHandle(sei.hProcess);
    }
    restart_receiver();
    wchar_t msg[200];
    _snwprintf(msg, 200, L"Your PC now shows as \"%s\" in Screen Mirroring.", g_name);
    balloon(APP, msg);
}

static void fix_firewall(void) {
    /* re-adds the inbound rule (needs admin, so ask via UAC) */
    wchar_t exe[MAX_PATH], args[1024];
    path_join(exe, MAX_PATH, g_dir, L"bin\\uxplay.exe");
    _snwprintf(args, 1024,
               L"/c netsh advfirewall firewall delete rule name=\"AirMirror\" & "
               L"netsh advfirewall firewall add rule name=\"AirMirror\" dir=in action=allow "
               L"program=\"%s\" enable=yes profile=any", exe);
    ShellExecuteW(NULL, L"runas", L"cmd.exe", args, NULL, SW_HIDE);
}

static void show_menu(HWND hwnd) {
    POINT pt;
    GetCursorPos(&pt);
    HMENU m = CreatePopupMenu();
    wchar_t status[160];
    _snwprintf(status, 160, L"Ready as \"%s\"", g_name);
    AppendMenuW(m, MF_STRING | MF_GRAYED, IDM_STATUS, status);
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING, IDM_RENAME, L"Change name...");
    AppendMenuW(m, MF_STRING | (autostart_enabled() ? MF_CHECKED : 0), IDM_AUTOSTART, L"Start with Windows");
    AppendMenuW(m, MF_STRING, IDM_RESTART, L"Restart receiver");
    AppendMenuW(m, MF_STRING, IDM_FIREWALL, L"iPhone can't find PC? Fix firewall");
    AppendMenuW(m, MF_STRING, IDM_LOG, L"Show log");
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING, IDM_QUIT, L"Quit AirMirror");
    SetForegroundWindow(hwnd);
    TrackPopupMenu(m, TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, pt.x, pt.y, 0, hwnd, NULL);
    PostMessageW(hwnd, WM_NULL, 0, 0);
    DestroyMenu(m);
}

static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_TRAY:
        if (LOWORD(lp) == WM_RBUTTONUP || LOWORD(lp) == WM_LBUTTONUP || LOWORD(lp) == WM_CONTEXTMENU) show_menu(hwnd);
        return 0;
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IDM_RENAME: rename_receiver(); break;
        case IDM_AUTOSTART: set_autostart(!autostart_enabled()); break;
        case IDM_RESTART: restart_receiver(); balloon(APP, L"Receiver restarted."); break;
        case IDM_FIREWALL: fix_firewall(); break;
        case IDM_LOG: open_log(); break;
        case IDM_QUIT: DestroyWindow(hwnd); break;
        }
        return 0;
    case WM_CHILD_EXITED: {
        /* receiver stopped on its own (e.g. mirroring window closed): restart it */
        DWORD alive = GetTickCount() - g_last_start;
        g_failures = (alive < 10000) ? g_failures + 1 : 0;
        stop_receiver();
        if (g_failures >= 5) {
            set_tip(L"AirMirror - stopped (see log)");
            balloon(APP, L"The receiver keeps stopping. Right-click the tray icon > Show log.");
            g_failures = 0;
        } else {
            Sleep(g_failures ? 1500 : 300);
            start_receiver();
        }
        return 0;
    }
    case WM_DESTROY:
        InterlockedExchange(&g_quitting, 1);
        stop_receiver();
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmdline, int show) {
    HANDLE mutex = CreateMutexW(NULL, TRUE, L"Local\\AirMirrorTray");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        MessageBoxW(NULL, L"AirMirror is already running.\nLook for its icon in the taskbar tray (near the clock).",
                    APP, MB_ICONINFORMATION);
        return 0;
    }
    BOOL quiet = cmdline && wcsstr(cmdline, L"--tray") != NULL;

    GetModuleFileNameW(NULL, g_dir, MAX_PATH);
    wchar_t *slash = wcsrchr(g_dir, L'\\');
    if (slash) *slash = 0;

    wchar_t local[MAX_PATH];
    if (SHGetFolderPathW(NULL, CSIDL_LOCAL_APPDATA, NULL, 0, local) != S_OK) wcscpy(local, g_dir);
    path_join(g_data, MAX_PATH, local, L"AirMirror");
    CreateDirectoryW(g_data, NULL);
    load_name();

    /* child receiver dies with us */
    g_job = CreateJobObjectW(NULL, NULL);
    if (g_job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION li;
        ZeroMemory(&li, sizeof(li));
        li.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(g_job, JobObjectExtendedLimitInformation, &li, sizeof(li));
    }

    WNDCLASSW wc = {0};
    wc.lpfnWndProc = wnd_proc;
    wc.hInstance = inst;
    wc.lpszClassName = L"AirMirrorTrayWnd";
    RegisterClassW(&wc);
    g_hwnd = CreateWindowW(wc.lpszClassName, APP, 0, 0, 0, 0, 0, HWND_MESSAGE, NULL, inst, NULL);

    ZeroMemory(&g_nid, sizeof(g_nid));
    g_nid.cbSize = sizeof(g_nid);
    g_nid.hWnd = g_hwnd;
    g_nid.uID = ID_TRAY;
    g_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    g_nid.uCallbackMessage = WM_TRAY;
    g_nid.hIcon = LoadIconW(inst, MAKEINTRESOURCEW(1));
    if (!g_nid.hIcon) g_nid.hIcon = LoadIconW(NULL, IDI_APPLICATION);
    wcscpy(g_nid.szTip, L"AirMirror");
    Shell_NotifyIconW(NIM_ADD, &g_nid);

    if (!start_receiver()) {
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        return 1;
    }
    if (!quiet) {
        wchar_t msg[256];
        _snwprintf(msg, 256, L"On your iPhone: Control Centre > Screen Mirroring > \"%s\"", g_name);
        balloon(L"AirMirror is ready", msg);
    }

    MSG m;
    while (GetMessageW(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    ReleaseMutex(mutex);
    return 0;
}
