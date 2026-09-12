#include "projectx_imgui_backend.h"

#include <SDL2/SDL.h>
#include <EGL/egl.h>

#include <vector>
#include <mutex>
#include <atomic>

#include "imgui.h"
#include "imgui_impl_sdl2.h"

void imgui_log_message(const char *format, ...);

namespace projectx::imgui_backend {

// Events arrive on the game's own event-pump thread via the SDL_PollEvent hook, which can fire while
// a frame is mid-flight. They are buffered here and fed to ImGui at a safe point in the frame.
static std::vector<SDL_Event> g_pending_events;
static std::mutex g_pending_events_mutex;

// The SDL_PollEvent hook is uninstalled only when the library itself unloads, so it keeps firing
// after an engine teardown that has already taken the consumer away. Without this gate the queue
// grows unattended until the overflow trim, and that erase runs concurrently with a drain from the
// render thread — which is how a plain uninject killed the client 30 seconds later.
static std::atomic<bool> g_accepting_events{false};

void init(void *window, void *gl_context) {
    // Only the window is strictly required: the SDL2 backend ignores the GL context pointer, which is
    // null anyway when the client runs on EGL.
    ImGui_ImplSDL2_InitForOpenGL((SDL_Window *)window, gl_context);
    g_accepting_events.store(true, std::memory_order_release);
}

void shutdown() {
    g_accepting_events.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> guard(g_pending_events_mutex);
        g_pending_events.clear();
        g_pending_events.shrink_to_fit();
    }
    ImGui_ImplSDL2_Shutdown();
}

void new_frame() { ImGui_ImplSDL2_NewFrame(); }

void drain_pending_events() {
    // Swap the batch out under the lock and process it unlocked: ImGui_ImplSDL2_ProcessEvent can be
    // slow, and holding the lock across it would stall the game's event-pump thread every frame.
    std::vector<SDL_Event> batch;
    {
        std::lock_guard<std::mutex> guard(g_pending_events_mutex);
        if (g_pending_events.empty()) return;
        batch.swap(g_pending_events);
    }
    for (const SDL_Event &ev : batch) {
        if (ev.type >= SDL_FIRSTEVENT && ev.type <= SDL_LASTEVENT) {
            ImGui_ImplSDL2_ProcessEvent(&ev);
        }
    }
}

void queue_event(void *event) {
    if (!event) return;
    if (!g_accepting_events.load(std::memory_order_acquire)) return;

    const SDL_Event *ev = (const SDL_Event *)event;
    if (ev->type < SDL_FIRSTEVENT || ev->type > SDL_LASTEVENT) {
        imgui_log_message("[Warn] Ignoring invalid SDL event with type: %u\n", ev->type);
        return;
    }

    std::lock_guard<std::mutex> guard(g_pending_events_mutex);
    g_pending_events.push_back(*ev);

    if (g_pending_events.size() > 8192) {
        g_pending_events.erase(g_pending_events.begin(),
                               g_pending_events.begin() + (g_pending_events.size() / 2));
        imgui_log_message("[Warn] Pending SDL event queue trimmed due to overflow.\n");
    }
}

int event_size() { return (int)sizeof(SDL_Event); }

void desktop_size(int *width, int *height) {
    SDL_DisplayMode mode;
    if (SDL_GetDesktopDisplayMode(0, &mode) == 0) {
        if (width) *width = mode.w;
        if (height) *height = mode.h;
    }
}

bool has_current_gl_context() { return eglGetCurrentContext() != EGL_NO_CONTEXT; }

void active_texture(GLenum texture_unit) { glActiveTexture(texture_unit); }

} // namespace projectx::imgui_backend
