#pragma once

#include <cstddef>

// GL types for the shared texture-upload path. Both platforms render through imgui_impl_opengl3; only
// the header that declares the types differs.
#if defined(_WIN32)
#include <windows.h>
#include <GL/gl.h>
// opengl32 and the Windows GL headers stop at GL 1.1, so anything newer has to be named here and
// resolved through wglGetProcAddress at runtime.
#ifndef GL_ACTIVE_TEXTURE
#define GL_ACTIVE_TEXTURE 0x84E0
#endif
#ifndef GL_TEXTURE0
#define GL_TEXTURE0 0x84C0
#endif
#else
#include <SDL2/SDL_opengl.h>
#endif

// The overlay's platform backend. projectx_imgui.cpp holds the frame lifecycle and the ~1800 lines of
// widget wrappers, all of which are platform-neutral; only the handful of calls below differ between
// the SDL2/EGL client on Linux and the Win32/WGL client on Windows.

namespace projectx::imgui_backend {

/// Binds ImGui to the host window. `window` is an SDL_Window* on Linux and an HWND on Windows;
/// `gl_context` may be null when the client drives its own context.
void init(void *window, void *gl_context);

void shutdown();

/// Per-frame backend tick, called between the renderer's NewFrame and ImGui::NewFrame.
void new_frame();

/// Feeds any events captured since the last frame into ImGui. On Linux these are queued by the
/// SDL_PollEvent hook; on Windows the WndProc subclass forwards them directly and this is a no-op.
void drain_pending_events();

/// Queues one host event for [drain_pending_events]. Only the SDL backend uses this — the Win32
/// backend consumes messages in its window procedure instead.
void queue_event(void *event);

/// Size in bytes of the host event struct, so the JVM can reinterpret event segments safely.
/// Returns 0 on backends that do not expose raw events.
int event_size();

/// Desktop resolution, used to clamp overlay window placement. Leaves the outputs untouched on
/// failure, so callers should seed them with a sane default.
void desktop_size(int *width, int *height);

/// True when the calling thread already has a GL context current, i.e. it is the render thread and
/// texture uploads may run inline rather than being deferred.
bool has_current_gl_context();

/// GL 1.3 multitexture unit selector, used to save and restore host state around texture uploads.
/// A direct call on Linux; on Windows it must go through the extension loader.
void active_texture(GLenum texture_unit);

} // namespace projectx::imgui_backend
