#include "projectx_imgui_backend.h"

#include <windows.h>

#include <atomic>
#include <mutex>
#include <vector>

#include "imgui.h"
#include "imgui_impl_win32.h"

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam);

void imgui_log_message(const char *format, ...);

namespace projectx::imgui_backend {

// The client has no SDL: input arrives as Win32 messages on the render view's window procedure, so
// the overlay subclasses it rather than hooking an event-poll function.
//
// Messages are QUEUED here and replayed on the render thread, never fed to ImGui inline. The window
// procedure runs on the game's message thread while the overlay builds and renders frames on other
// threads, and ImGui keeps global per-frame state that is not safe to mutate concurrently — doing so
// trips its internal ImVector bounds assert, which aborts the process outright.
//
// Swallowing therefore cannot consult the handler's return value, which is only known once the event
// is actually processed. It uses ImGui's capture flags from the last completed frame instead: one
// frame of latency on the hand-off between overlay and game, versus a crash.
struct PendingMessage {
    HWND hwnd;
    UINT msg;
    WPARAM wparam;
    LPARAM lparam;
};

static WNDPROC g_original_wndproc = nullptr;
static HWND g_hwnd = nullptr;
static std::vector<PendingMessage> g_pending;
static std::mutex g_pending_mutex;
static std::atomic<bool> g_accepting_events{false};
static std::atomic<bool> g_capture_mouse{false};
static std::atomic<bool> g_capture_keyboard{false};

static bool overlay_wants(UINT msg) {
    if (msg >= WM_MOUSEFIRST && msg <= WM_MOUSELAST) return g_capture_mouse.load(std::memory_order_acquire);
    if (msg >= WM_KEYFIRST && msg <= WM_KEYLAST) return g_capture_keyboard.load(std::memory_order_acquire);
    return false;
}

static LRESULT CALLBACK overlay_wndproc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    if (g_accepting_events.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> guard(g_pending_mutex);
            g_pending.push_back({hwnd, msg, wparam, lparam});
            if (g_pending.size() > 8192) {
                g_pending.erase(g_pending.begin(), g_pending.begin() + (g_pending.size() / 2));
                imgui_log_message("[Warn] Pending Win32 message queue trimmed due to overflow.\n");
            }
        }
        // The overlay consumed it — swallow so the game does not also act on it. This is what stops
        // clicks on overlay windows from reaching the scene.
        if (overlay_wants(msg)) return 1;
    }
    return CallWindowProcW(g_original_wndproc, hwnd, msg, wparam, lparam);
}

void init(void *window, void *gl_context) {
    (void)gl_context;   // the client owns the WGL context; ImGui_ImplWin32 does not need it

    g_hwnd = (HWND)window;
    if (!g_hwnd) {
        imgui_log_message("[Error] imgui_backend::init called with a null HWND\n");
        return;
    }

    ImGui_ImplWin32_Init(g_hwnd);
    g_accepting_events.store(true, std::memory_order_release);

    // Subclass last, so a message arriving mid-init cannot reach ImGui before it is ready.
    if (!g_original_wndproc) {
        g_original_wndproc =
            (WNDPROC)SetWindowLongPtrW(g_hwnd, GWLP_WNDPROC, (LONG_PTR)overlay_wndproc);
        if (!g_original_wndproc) {
            imgui_log_message("[Error] SetWindowLongPtrW(GWLP_WNDPROC) failed: %lu\n",
                              (unsigned long)GetLastError());
        }
    }
}

void shutdown() {
    // Stop queueing before the procedure is restored, so nothing is left in the queue for a frame
    // that will never run.
    g_accepting_events.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> guard(g_pending_mutex);
        g_pending.clear();
    }
    // Restore the game's own procedure before tearing ImGui down, or a message arriving afterwards
    // would land in a handler whose context has already been destroyed.
    if (g_original_wndproc && g_hwnd) {
        SetWindowLongPtrW(g_hwnd, GWLP_WNDPROC, (LONG_PTR)g_original_wndproc);
        g_original_wndproc = nullptr;
    }
    ImGui_ImplWin32_Shutdown();
}

void new_frame() { ImGui_ImplWin32_NewFrame(); }

void drain_pending_events() {
    // Swap the batch out under the lock and process it unlocked: the handler can be slow, and holding
    // the lock across it would stall the game's message thread every frame.
    std::vector<PendingMessage> batch;
    {
        std::lock_guard<std::mutex> guard(g_pending_mutex);
        if (!g_pending.empty()) batch.swap(g_pending);
    }
    for (const PendingMessage &m : batch) {
        ImGui_ImplWin32_WndProcHandler(m.hwnd, m.msg, m.wparam, m.lparam);
    }

    // Refresh what the window procedure swallows, now that this frame's capture state is settled.
    const ImGuiIO &io = ImGui::GetIO();
    g_capture_mouse.store(io.WantCaptureMouse, std::memory_order_release);
    g_capture_keyboard.store(io.WantCaptureKeyboard, std::memory_order_release);
}

void queue_event(void *) {}

int event_size() { return 0; }

void desktop_size(int *width, int *height) {
    int w = GetSystemMetrics(SM_CXSCREEN);
    int h = GetSystemMetrics(SM_CYSCREEN);
    if (w > 0 && width) *width = w;
    if (h > 0 && height) *height = h;
}

bool has_current_gl_context() { return wglGetCurrentContext() != nullptr; }

void active_texture(GLenum texture_unit) {
    using ActiveTextureFn = void(APIENTRY *)(GLenum);
    // wglGetProcAddress only answers with a context current, and it returns null for GL 1.1 names,
    // so this is resolved lazily on the render thread rather than at load time.
    static ActiveTextureFn resolved = nullptr;
    if (!resolved) resolved = (ActiveTextureFn)wglGetProcAddress("glActiveTexture");
    if (resolved) resolved(texture_unit);
}

} // namespace projectx::imgui_backend

// The SDL client hooks SDL_PollEvent to capture input; on Windows the WndProc subclass installed in
// init() does that job, so these exist only to satisfy the bootstrap's platform-neutral call sites.
extern "C" void ProjectX_InitializeSDLHook() {}
extern "C" void ProjectX_CleanupSDLHook() {}
