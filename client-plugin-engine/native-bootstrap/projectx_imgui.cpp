#include "projectx_imgui_backend.h"
#include "projectx_platform.h"
#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <string>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include "imgui.h"
#include "imgui_impl_opengl3.h"
#include "imgui_internal.h" // For setting ErrorCallback
#ifdef PROJECTX_VULKAN
#include "projectx_vulkan.h"
#endif

// Which renderer backend the overlay was bound to. The OpenGL client presents through SwapBuffers and
// the Vulkan client through vkQueuePresentKHR; whichever the running client calls initialises this once.
enum class OverlayRenderer { None, OpenGL, Vulkan };
static OverlayRenderer g_renderer = OverlayRenderer::None;

// External log file from bootstrap
extern FILE *log_file;

// Real user home from the passwd db (NOT the bolt-overridden $HOME); defined in
// projectx_bootstrap.cpp.

// Error logging function following the pattern from projectx_bootstrap.cpp
void imgui_log_message(const char *format, ...) {
    va_list args;
    va_start(args, format);
    if (log_file) {
        std::fprintf(log_file, "[ImGui] ");
        std::vfprintf(log_file, format, args);
        std::fflush(log_file);
    } else {
        std::fprintf(stderr, "[ImGui] ");
        std::vfprintf(stderr, format, args);
        std::fflush(stderr);
    }
    va_end(args);
}

void imgui_log_error(const char* function_name, const char* error_msg) {
    imgui_log_message("[ERROR] %s: %s\n", function_name, error_msg);
}

// Development vs production configuration
#ifdef PROJECTX_DEBUG
    #define PROJECTX_IMGUI_ENABLE_ASSERTS 1
    #define PROJECTX_IMGUI_ENABLE_DETAILED_LOGGING 1
#else
    #define PROJECTX_IMGUI_ENABLE_ASSERTS 0
    #define PROJECTX_IMGUI_ENABLE_DETAILED_LOGGING 0
#endif

// ==========================================
// Render-thread GL texture queue.
//
// Every GL/EGL texture op MUST run on the render thread while the game's EGL
// context is current. Doing GL work from any other thread (notably the JVM
// Cleaner during texture GC) previously required making a second, game-shared
// EGL context current off-thread; that raced the game's own render-context
// teardown and made its eglMakeCurrent(dpy, NO_SURFACE, NO_SURFACE, NO_CONTEXT)
// return EGL_FALSE, which the client treats as fatal ("Failed to release EGL
// context"). So texture create/destroy are queued here and drained from
// ProjectX_ImGui_NewFrame, on the render thread, with the context current.
// ==========================================

static std::mutex g_gl_queue_mutex;
static std::condition_variable g_gl_create_cv;
static std::vector<GLuint> g_pending_tex_deletes;

// A texture requested before the render thread gets to it. The handle's meaning depends on the
// renderer: a GL texture name for OpenGL, an ImTextureData pointer for Vulkan.
struct PendingTexCreate {
    const void *pixels;
    int width;
    int height;
    int64_t result;
    bool done;
};
static std::vector<PendingTexCreate *> g_pending_tex_creates;

// Uploads a texture via GL. Caller MUST be on the render thread with the game's
// GL context current. Host GL state is saved and restored.
static GLuint ProjectX_GL_CreateTextureNow(const void *pixels, int width, int height) {
    GLint prev_active_tex = 0, prev_tex_binding = 0, prev_unpack_align = 0;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active_tex);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex_binding);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &prev_unpack_align);

    GLuint texture = 0;
    glGenTextures(1, &texture);
    projectx::imgui_backend::active_texture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glPixelStorei(GL_UNPACK_ALIGNMENT, prev_unpack_align);
    glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex_binding);
    projectx::imgui_backend::active_texture((GLenum)prev_active_tex);
    return texture;
}

// ==========================================
// Vulkan user textures.
//
// The Vulkan renderer never hands out raw GPU handles: a texture from the JVM becomes an ImGui user
// texture (ImTextureData), which the backend uploads during the next render and draw calls reference
// through ImTextureRef. ImGui's texture list is not thread safe, so registration and destruction run
// on the render thread only - the thread that runs the client's present.
// ==========================================

static thread_local bool t_is_render_thread = false;
static std::vector<ImTextureData *> g_pending_user_deletes;
static std::vector<ImTextureData *> g_destroying_user_textures;

static ImTextureData *ProjectX_UserTexture_CreateNow(const void *pixels, int width, int height) {
    ImTextureData *tex = IM_NEW(ImTextureData)();
    tex->Create(ImTextureFormat_RGBA32, width, height);
    std::memcpy(tex->GetPixels(), pixels, (size_t)width * (size_t)height * 4);
    ImGui::RegisterUserTexture(tex);
    return tex;
}

static int64_t ProjectX_CreateTextureNow(const void *pixels, int width, int height) {
    if (g_renderer == OverlayRenderer::Vulkan) {
        return (int64_t)(intptr_t)ProjectX_UserTexture_CreateNow(pixels, width, height);
    }
    return (int64_t)ProjectX_GL_CreateTextureNow(pixels, width, height);
}

// Drains queued texture create/destroy. MUST run on the render thread - with the game's GL context
// current for OpenGL - before ImGui::NewFrame. Everything (including create) runs under the lock so a
// timed-out creator can detach its request without a use-after-free.
static void ProjectX_DrainPendingTextures() {
    if (g_renderer == OverlayRenderer::Vulkan) {
        // A texture the backend has finished destroying is no longer referenced by any draw data.
        for (auto it = g_destroying_user_textures.begin(); it != g_destroying_user_textures.end();) {
            ImTextureData *tex = *it;
            if (tex->Status == ImTextureStatus_Destroyed) {
                ImGui::UnregisterUserTexture(tex);
                IM_DELETE(tex);
                it = g_destroying_user_textures.erase(it);
            } else {
                ++it;
            }
        }
    }

    std::lock_guard<std::mutex> lock(g_gl_queue_mutex);
    if (!g_pending_tex_deletes.empty()) {
        glDeleteTextures((GLsizei)g_pending_tex_deletes.size(), g_pending_tex_deletes.data());
        g_pending_tex_deletes.clear();
    }
    for (ImTextureData *tex : g_pending_user_deletes) {
        tex->WantDestroyNextFrame = true;
        g_destroying_user_textures.push_back(tex);
    }
    g_pending_user_deletes.clear();
    if (!g_pending_tex_creates.empty()) {
        for (PendingTexCreate *req : g_pending_tex_creates) {
            req->result = ProjectX_CreateTextureNow(req->pixels, req->width, req->height);
            req->done = true;
        }
        g_pending_tex_creates.clear();
        g_gl_create_cv.notify_all();
    }
}

static ImTextureRef ProjectX_TextureRef(int64_t texture) {
    if (g_renderer == OverlayRenderer::Vulkan) {
        return ((ImTextureData *)(intptr_t)texture)->GetTexRef();
    }
    return ImTextureRef((ImTextureID)texture);
}

extern "C" {
    // Frame state tracking to prevent processing events during unsafe times
    static bool g_imgui_frame_in_progress = false;
    
    // Export frame state for SDL hook
    bool ProjectX_ImGui_IsFrameInProgress() {
        return g_imgui_frame_in_progress;
    }

    void ProjectX_ImGui_QueueEvent(void* event) {
        projectx::imgui_backend::queue_event(event);
    }

    int ProjectX_SDL_EventSize() {
        return projectx::imgui_backend::event_size();
    }

    static void ProjectX_ImGui_ErrorCallback(ImGuiContext* ctx, void* user_data, const char* msg) {
        (void)ctx; (void)user_data;
        imgui_log_message("[ErrorCallback] %s\n", msg ? msg : "<null message>");
    }

    static void setupFonts() {
        ImGuiIO& io = ImGui::GetIO();

        std::vector<std::string> candidates;
#ifdef _WIN32
        // %SystemRoot% rather than a literal drive, so the lookup follows the Windows install.
        const char* systemRoot = std::getenv("SystemRoot");
        const std::string fontDir = std::string(systemRoot ? systemRoot : "C:\\Windows") + "\\Fonts\\";
        for (const char* name : {"segoeui.ttf", "consola.ttf", "tahoma.ttf", "arial.ttf"}) {
            candidates.push_back(fontDir + name);
        }
#else
        candidates = {
            "/usr/share/fonts/TTF/TinosNerdFontPropo-Bold.ttf",
            "/usr/share/fonts/TTF/FiraCodeNerdFont-Regular.ttf",
            "/usr/share/fonts/TTF/IosevkaNerdFont-Regular.ttf",
            "/usr/share/fonts/Adwaita/AdwaitaSans-Regular.ttf",
            "/usr/share/fonts/noto/NotoSans-Regular.ttf",
        };
#endif

        for (const std::string& fontPath : candidates) {
            if (io.Fonts->AddFontFromFileTTF(fontPath.c_str(), 18.0f) != nullptr) {
                imgui_log_message("Loaded font: %s at 18px\n", fontPath.c_str());
                return;
            }
        }

        // The built-in font is a 13px bitmap, so asking for 18px upscales it and the whole overlay
        // reads as blurry. Reaching here means every system font above was missing.
        ImFontConfig config;
        config.SizePixels = 18.0f;
        io.Fonts->AddFontDefault(&config);
        imgui_log_message("No system font found; falling back to the upscaled built-in font at 18px\n");
    }

    /// The context both renderers share: ini location, error recovery, fonts and style.
    static void createContext() {
        IMGUI_CHECKVERSION();
        ImGui::CreateContext();
        ImGuiIO& io = ImGui::GetIO();

        // Persist imgui.ini under ~/.projectx alongside the other configs.
        // ImGui defaults IniFilename to "imgui.ini" relative to the CWD (the
        // folder the launcher was started from), and it stores this pointer
        // verbatim without copying — so the buffer must outlive the context.
        {
            static char ini_path[1024];
            const char *home = projectx::platform::home_dir();
            char projectx_dir[768];
            std::snprintf(projectx_dir, sizeof(projectx_dir), "%s/.projectx", home);
            projectx::platform::make_directory(projectx_dir);   // best-effort; ignore EEXIST
            std::snprintf(ini_path, sizeof(ini_path), "%s/imgui.ini", projectx_dir);
            io.IniFilename = ini_path;
            imgui_log_message("imgui.ini path set to %s\n", ini_path);
        }

        // Configure error recovery according to ImGui best practices
        io.ConfigErrorRecovery = true;
        io.ConfigErrorRecoveryEnableAssert = PROJECTX_IMGUI_ENABLE_ASSERTS;
        io.ConfigErrorRecoveryEnableDebugLog = PROJECTX_IMGUI_ENABLE_DETAILED_LOGGING;
        io.ConfigErrorRecoveryEnableTooltip = PROJECTX_IMGUI_ENABLE_DETAILED_LOGGING;
        // Ensure at least one recovery output is enabled to satisfy ImGui sanity checks
        if (!io.ConfigErrorRecoveryEnableAssert &&
            !io.ConfigErrorRecoveryEnableDebugLog &&
            !io.ConfigErrorRecoveryEnableTooltip) {
            io.ConfigErrorRecoveryEnableDebugLog = true;
        }

        // Safer window movement/resizing configuration
        io.ConfigWindowsMoveFromTitleBarOnly = true;
        io.ConfigWindowsResizeFromEdges = true;

        // Install error callback so issues are logged instead of aborting
        ImGuiContext* ctx = ImGui::GetCurrentContext();
        if (ctx) {
            ctx->ErrorCallback = ProjectX_ImGui_ErrorCallback;
            ctx->ErrorCallbackUserData = nullptr;
        }
        
        setupFonts();

        ImGui::StyleColorsDark();
    }

    void ProjectX_ImGui_Init(void* sdl_window, void* gl_context) {
        imgui_log_message("Initializing ImGui with SDL window: %p, GL context: %p\n", sdl_window, gl_context);

        // Idempotent for hot-reload: a reloaded engine reuses the existing ImGui context + GL
        // backend (the game's GL context is unchanged), so creating a second context here would
        // corrupt state and break texture creation. The engine never calls Shutdown on reload.
        if (ImGui::GetCurrentContext()) {
            imgui_log_message("ProjectX_ImGui_Init: context already exists; reusing (hot-reload).\n");
            return;
        }

        // Only the SDL window is strictly required by the SDL2 backend. The GL context pointer
        // is unused by the backend in master branch and may be null when the application uses EGL.
        if (!sdl_window) {
            imgui_log_error("ProjectX_ImGui_Init", "Invalid SDL window pointer");
            return;
        }
        if (!gl_context) {
            imgui_log_message("ProjectX_ImGui_Init: GL context pointer is null (expected when using EGL); proceeding anyway.\n");
        }
        
        try {
            createContext();
            projectx::imgui_backend::init(sdl_window, gl_context);
            ImGui_ImplOpenGL3_Init("#version 130");
            g_renderer = OverlayRenderer::OpenGL;

            imgui_log_message("ImGui initialization completed successfully\n");
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Init", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Init", "Unknown exception during initialization");
        }
    }

    void ProjectX_ImGui_InitVulkan(void* window, void* instance, void* physical_device, void* device, void* queue,
                                   int queue_family, int api_version, int64_t surface_from_swapchain,
                                   int64_t width_offset, int64_t height_offset) {
        imgui_log_message("Initializing ImGui for Vulkan with window: %p, device: %p\n", window, device);

        // Idempotent for hot-reload, exactly like the OpenGL path: the device and swapchain are unchanged
        // across a reload, so the existing context and Vulkan backend are reused.
        if (ImGui::GetCurrentContext()) {
            imgui_log_message("ProjectX_ImGui_InitVulkan: context already exists; reusing (hot-reload).\n");
            return;
        }
#ifdef PROJECTX_VULKAN
        if (!window) {
            imgui_log_error("ProjectX_ImGui_InitVulkan", "Invalid window handle");
            return;
        }
        try {
            createContext();
            projectx::imgui_backend::init(window, nullptr);
            if (!projectx::vulkan::init(instance, physical_device, device, queue, (uint32_t)queue_family,
                                        (uint32_t)api_version, surface_from_swapchain, width_offset, height_offset)) {
                // Leave no context behind, so every later frame stays a no-op instead of driving a
                // renderer that does not exist.
                projectx::imgui_backend::shutdown();
                ImGui::DestroyContext();
                imgui_log_error("ProjectX_ImGui_InitVulkan", "Vulkan renderer could not bind to the client");
                return;
            }
            g_renderer = OverlayRenderer::Vulkan;
            imgui_log_message("ImGui Vulkan initialization completed successfully\n");
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_InitVulkan", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_InitVulkan", "Unknown exception during initialization");
        }
#else
        (void)window; (void)instance; (void)physical_device; (void)device; (void)queue; (void)queue_family;
        (void)api_version; (void)surface_from_swapchain; (void)width_offset; (void)height_offset;
        imgui_log_error("ProjectX_ImGui_InitVulkan", "This bootstrap was built without the Vulkan renderer");
#endif
    }

    void ProjectX_Vulkan_BeginFrame(void* present_info) {
#ifdef PROJECTX_VULKAN
        projectx::vulkan::begin_frame(present_info);
#else
        (void)present_info;
#endif
    }

    void* ProjectX_Vulkan_EndFrame(void* present_info) {
#ifdef PROJECTX_VULKAN
        return projectx::vulkan::end_frame(present_info);
#else
        return present_info;
#endif
    }

    void ProjectX_ImGui_Shutdown() {
        imgui_log_message("Shutting down ImGui\n");
        try {
            if (g_renderer == OverlayRenderer::OpenGL) {
                ImGui_ImplOpenGL3_Shutdown();
            }
#ifdef PROJECTX_VULKAN
            if (g_renderer == OverlayRenderer::Vulkan) {
                projectx::vulkan::shutdown();
            }
#endif
            projectx::imgui_backend::shutdown();
            ImGui::DestroyContext();
            g_renderer = OverlayRenderer::None;
            imgui_log_message("ImGui shutdown completed successfully\n");
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Shutdown", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Shutdown", "Unknown exception during shutdown");
        }
    }

    // A renderer that failed to bind leaves no context, and the frame driver keeps calling in every
    // frame; say so once rather than twice per frame.
    static bool g_reported_missing_context = false;

    static bool ProjectX_HasContext(const char* function_name) {
        if (ImGui::GetCurrentContext()) return true;
        if (!g_reported_missing_context) {
            imgui_log_error(function_name, "No ImGui context available (further frames are skipped silently)");
            g_reported_missing_context = true;
        }
        return false;
    }

    void ProjectX_ImGui_NewFrame() {
        if (!ProjectX_HasContext("ProjectX_ImGui_NewFrame")) {
            return;
        }
        
        try {
            // Safety check: if the previous frame wasn't completed, complete it now
            if (g_imgui_frame_in_progress) {
                imgui_log_message("[Recovery] Previous frame was incomplete, calling Render() to complete it\n");
                try {
                    ImGui::Render();
                } catch (...) {
                    imgui_log_error("ProjectX_ImGui_NewFrame", "Exception during recovery render");
                }
                g_imgui_frame_in_progress = false;
            }
            
            projectx::imgui_backend::drain_pending_events();
            
            // Mark frame as in progress (events will be queued until Render())
            g_imgui_frame_in_progress = true;
            t_is_render_thread = true;

            // Render thread (game GL context current on OpenGL): safe point for queued texture ops.
            ProjectX_DrainPendingTextures();

#ifdef PROJECTX_VULKAN
            if (g_renderer == OverlayRenderer::Vulkan) {
                projectx::vulkan::new_frame();
            } else
#endif
            {
                ImGui_ImplOpenGL3_NewFrame();
            }
            projectx::imgui_backend::new_frame();
#ifdef PROJECTX_VULKAN
            // Draw in swapchain pixels: the render pass covers exactly the image being presented.
            uint32_t width = 0, height = 0;
            if (g_renderer == OverlayRenderer::Vulkan && projectx::vulkan::frame_extent(&width, &height)) {
                ImGui::GetIO().DisplaySize = ImVec2((float)width, (float)height);
            }
#endif
            ImGui::NewFrame();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_NewFrame", e.what());
            // Ensure flag is cleared on error to prevent recovery loop
            g_imgui_frame_in_progress = false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_NewFrame", "Unknown exception during frame initialization");
            // Ensure flag is cleared on error to prevent recovery loop
            g_imgui_frame_in_progress = false;
        }
    }

    void ProjectX_ImGui_Render() {
        if (!ProjectX_HasContext("ProjectX_ImGui_Render")) {
            return;
        }
        
        try {
            ImGui::Render();
            ImDrawData* draw_data = ImGui::GetDrawData();
            if (!draw_data) {
                imgui_log_error("ProjectX_ImGui_Render", "ImGui draw data is null");
                return;
            }
#ifdef PROJECTX_VULKAN
            if (g_renderer == OverlayRenderer::Vulkan) {
                projectx::vulkan::render(draw_data);
            } else
#endif
            {
                ImGui_ImplOpenGL3_RenderDrawData(draw_data);
            }

            // Frame is complete, safe to process events again
            g_imgui_frame_in_progress = false;
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Render", e.what());
            g_imgui_frame_in_progress = false; // Ensure flag is cleared on error
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Render", "Unknown exception during render");
            g_imgui_frame_in_progress = false; // Ensure flag is cleared on error
        }
    }

    void ProjectX_ImGui_ProcessEvent(void* event) {
        // This function is kept for compatibility but is no longer used
        // Events are now processed directly in the SDL hook via ImGui_ImplSDL2_ProcessEvent
        // Keeping this as a no-op to avoid breaking the JVM interface
        (void)event;
    }

    bool ProjectX_ImGui_WantCaptureMouse() {
        if (!ImGui::GetCurrentContext()) {
            return false; // ImGui not initialized yet
        }
        return ImGui::GetIO().WantCaptureMouse;
    }

    // This frame's vertical wheel movement, positive away from the user. The overlay's window procedure keeps
    // the wheel from the game while the mouse is over an overlay window, so this is the only place to read it.
    float ProjectX_ImGui_GetMouseWheel() {
        return ImGui::GetIO().MouseWheel;
    }

    bool ProjectX_ImGui_WantCaptureKeyboard() {
        if (!ImGui::GetCurrentContext()) {
            return false; // ImGui not initialized yet
        }
        return ImGui::GetIO().WantCaptureKeyboard;
    }

    // ===== Window Functions =====
    
    bool ProjectX_ImGui_Begin(const char* name, bool* p_open, int flags) {
        // Validate ImGui context exists
        if (!ImGui::GetCurrentContext()) {
            imgui_log_error("ProjectX_ImGui_Begin", "No ImGui context available");
            return false;
        }
        
        if (!name) {
            imgui_log_error("ProjectX_ImGui_Begin", "Window name is null");
            return false;
        }
        
        try {
            // ImGui::Begin can handle nullptr for p_open (window won't have close button)
            return ImGui::Begin(name, p_open, flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Begin", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Begin", "Unknown exception during window begin");
            return false;
        }
    }
    
    void ProjectX_ImGui_End() {
        if (!ImGui::GetCurrentContext()) {
            imgui_log_error("ProjectX_ImGui_End", "No ImGui context available");
            return;
        }
        
        try {
            ImGui::End();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_End", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_End", "Unknown exception during window end");
        }
    }
    
    void ProjectX_ImGui_SetNextWindowPos(float x, float y, int cond, float pivot_x, float pivot_y) {
        try {
            ImGui::SetNextWindowPos(ImVec2(x, y), cond, ImVec2(pivot_x, pivot_y));
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SetNextWindowPos", "Unknown exception during window position setting");
        }
    }
    
    void ProjectX_ImGui_SetNextWindowSize(float width, float height, int cond) {
        try {
            // Validate reasonable window size to prevent issues
            float safe_width = (width > 0 && width < 10000) ? width : 100;
            float safe_height = (height > 0 && height < 10000) ? height : 100;
            ImGui::SetNextWindowSize(ImVec2(safe_width, safe_height), cond);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SetNextWindowSize", "Unknown exception during window size setting");
        }
    }
    
    // ===== Basic Widget Functions =====
    
    void ProjectX_ImGui_Text(const char* text) {
        if (!text) {
            imgui_log_error("ProjectX_ImGui_Text", "Text pointer is null");
            return;
        }
        
        try {
            // Use TextUnformatted to avoid format string vulnerabilities
            ImGui::TextUnformatted(text);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Text", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Text", "Unknown exception during text display");
        }
    }

    void ProjectX_ImGui_TextWrapped(const char* text) {
        if (!text) {
            imgui_log_error("ProjectX_ImGui_TextWrapped", "Text pointer is null");
            return;
        }
        
        try {
            // Use Push/PopTextWrapPos with TextUnformatted for safety
            ImGui::PushTextWrapPos(0.0f);
            ImGui::TextUnformatted(text);
            ImGui::PopTextWrapPos();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_TextWrapped", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TextWrapped", "Unknown exception during wrapped text display");
        }
    }
    
    bool ProjectX_ImGui_Button(const char* label, float width, float height) {
        if (!label) {
            imgui_log_error("ProjectX_ImGui_Button", "Label pointer is null, using empty string");
            label = "";
        }
        
        try {
            return ImGui::Button(label, ImVec2(width, height));
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Button", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Button", "Unknown exception during button rendering");
            return false;
        }
    }
    
    bool ProjectX_ImGui_Checkbox(const char* label, bool* v) {
        if (!label || !v) {
            return false;
        }
        
        try {
            return ImGui::Checkbox(label, v);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Checkbox", "Unknown exception during checkbox");
            return false;
        }
    }
    
    bool ProjectX_ImGui_InputInt(const char* label, int* v, int step, int step_fast, int flags) {
        if (!label) {
            imgui_log_error("ProjectX_ImGui_InputInt", "Label parameter is null");
            return false;
        }
        if (!v) {
            imgui_log_error("ProjectX_ImGui_InputInt", "Value pointer is null");
            return false;
        }
        
        // Sanitize steps
        if (step < 0) step = 0;
        if (step_fast < 0) step_fast = 0;
        
        try {
            return ImGui::InputInt(label, v, step, step_fast, flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_InputInt", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_InputInt", "Unknown exception during InputInt");
            return false;
        }
    }
    
    bool ProjectX_ImGui_InputText(const char* label, char* buf, size_t buf_size, int flags) {
        // Enhanced validation with specific error messages
        if (!label) {
            imgui_log_error("ProjectX_ImGui_InputText", "Label parameter is null");
            return false;
        }
        
        if (!buf) {
            imgui_log_error("ProjectX_ImGui_InputText", "Buffer parameter is null");
            return false;
        }
        
        if (buf_size == 0) {
            imgui_log_error("ProjectX_ImGui_InputText", "Buffer size is zero");
            return false;
        }
        
        if (buf_size > 1048576) { // 1MB limit
            imgui_log_error("ProjectX_ImGui_InputText", "Buffer size exceeds reasonable limit (1MB)");
            return false;
        }
        
        // Ensure buffer is null-terminated
        buf[buf_size - 1] = '\0';
        
        try {
            // Cap buffer size to prevent excessive memory usage
            size_t safe_size = (buf_size > 65536) ? 65536 : buf_size;
            return ImGui::InputText(label, buf, safe_size, flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_InputText", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_InputText", "Unknown exception during text input");
            return false;
        }
    }
    
    bool ProjectX_ImGui_SliderFloat(const char* label, float* v, float v_min, float v_max, const char* format, int flags) {
        if (!label) {
            imgui_log_error("ProjectX_ImGui_SliderFloat", "Label parameter is null");
            return false;
        }
        
        if (!v) {
            imgui_log_error("ProjectX_ImGui_SliderFloat", "Value pointer is null");
            return false;
        }
        
        // Validate min/max range
        if (v_min > v_max) {
            imgui_log_error("ProjectX_ImGui_SliderFloat", "Min value greater than max value, swapping");
            float temp = v_min;
            v_min = v_max;
            v_max = temp;
        }
        
        // Use default format if null
        if (!format) {
            format = "%.3f";
        }
        
        try {
            return ImGui::SliderFloat(label, v, v_min, v_max, format, flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_SliderFloat", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SliderFloat", "Unknown exception during slider rendering");
            return false;
        }
    }
    
    bool ProjectX_ImGui_SliderInt(const char* label, int* v, int v_min, int v_max, const char* format, int flags) {
        if (!label || !v) {
            return false;
        }
        
        // Validate min/max range
        if (v_min > v_max) {
            int temp = v_min;
            v_min = v_max;
            v_max = temp;
        }
        
        // Use default format if null
        if (!format) {
            format = "%d";
        }
        
        try {
            return ImGui::SliderInt(label, v, v_min, v_max, format, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SliderInt", "Unknown exception during slider rendering");
            return false;
        }
    }
    
    // ===== Layout Functions =====
    
    void ProjectX_ImGui_Separator() {
        try {
            ImGui::Separator();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_Separator", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Separator", "Unknown exception during separator rendering");
        }
    }
    
    void ProjectX_ImGui_SameLine(float offset_from_start_x, float spacing) {
        try {
            ImGui::SameLine(offset_from_start_x, spacing);
        } catch (...) {
            // Silently handle errors
        }
    }
    
    void ProjectX_ImGui_NewLine() {
        try {
            ImGui::NewLine();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    void ProjectX_ImGui_Spacing() {
        try {
            ImGui::Spacing();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    // ===== Tree/Collapsing Header Functions =====
    
    bool ProjectX_ImGui_TreeNode(const char* label) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::TreeNode(label);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TreeNode", "Unknown exception during tree node");
            return false;
        }
    }
    
    bool ProjectX_ImGui_TreeNodeEx(const char* label, int flags) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::TreeNodeEx(label, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TreeNodeEx", "Unknown exception during tree node");
            return false;
        }
    }
    
    void ProjectX_ImGui_TreePop() {
        try {
            ImGui::TreePop();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TreePop", "Unknown exception during tree pop");
        }
    }

    // Routed through ImGui rather than AWT: the engine's JVM runs headless so the texture loader never
    // opens a display, and java.awt.Toolkit.getSystemClipboard() throws outright in that mode. ImGui hands
    // this to whichever platform backend the client is using.
    void ProjectX_ImGui_SetClipboardText(const char* text) {
        if (!text) {
            return;
        }

        try {
            ImGui::SetClipboardText(text);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SetClipboardText", "Unknown exception during clipboard set");
        }
    }

    // Draws its glyph as vector geometry, so directional buttons stay legible under the default font, whose
    // range stops at U+00FF and cannot render any of the arrow codepoints.
    bool ProjectX_ImGui_ArrowButton(const char* id, int dir) {
        if (!id) {
            return false;
        }

        try {
            return ImGui::ArrowButton(id, static_cast<ImGuiDir>(dir));
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_ArrowButton", "Unknown exception during arrow button");
            return false;
        }
    }
    
    bool ProjectX_ImGui_CollapsingHeader(const char* label, int flags) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::CollapsingHeader(label, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_CollapsingHeader", "Unknown exception during collapsing header");
            return false;
        }
    }
    
    // ===== Combo Box Functions =====
    
    bool ProjectX_ImGui_BeginCombo(const char* label, const char* preview_value, int flags) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::BeginCombo(label, preview_value, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginCombo", "Unknown exception during begin combo");
            return false;
        }
    }
    
    void ProjectX_ImGui_EndCombo() {
        try {
            ImGui::EndCombo();
        } catch (...) {
            // Silently handle errors
        }
    }

    bool ProjectX_ImGui_Combo_StringList(const char* label, int* current_item, const char* items_separated_by_zeroes, int popup_max_height_in_items = -1) {
        if (!label) {
            return false;
        }

        try {
            return ImGui::Combo(label, current_item, items_separated_by_zeroes, popup_max_height_in_items);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Combo_StringList", "Unknown exception during combo string list");
            return false;
        }

    }
    
    // ===== Selectable Functions =====
    
    bool ProjectX_ImGui_Selectable(const char* label, bool selected, int flags, float size_x, float size_y) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::Selectable(label, selected, flags, ImVec2(size_x, size_y));
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_Selectable", "Unknown exception during selectable");
            return false;
        }
    }
    
    bool ProjectX_ImGui_SelectableWithBuffer(const char* label, bool* p_selected, int flags, float size_x, float size_y) {
        if (!label) {
            imgui_log_error("ProjectX_ImGui_SelectableWithBuffer", "Label pointer is null");
            return false;
        }
        
        if (!p_selected) {
            imgui_log_error("ProjectX_ImGui_SelectableWithBuffer", "Selected buffer pointer is null");
            return false;
        }
        
        try {
            return ImGui::Selectable(label, p_selected, flags, ImVec2(size_x, size_y));
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_SelectableWithBuffer", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SelectableWithBuffer", "Unknown exception during selectable rendering");
            return false;
        }
    }
    
    // ===== Menu Functions =====
    
    bool ProjectX_ImGui_BeginMenuBar() {
        try {
            return ImGui::BeginMenuBar();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginMenuBar", "Unknown exception during begin menu bar");
            return false;
        }
    }
    
    void ProjectX_ImGui_EndMenuBar() {
        try {
            ImGui::EndMenuBar();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    bool ProjectX_ImGui_BeginMenu(const char* label, bool enabled) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::BeginMenu(label, enabled);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginMenu", "Unknown exception during begin menu");
            return false;
        }
    }
    
    void ProjectX_ImGui_EndMenu() {
        try {
            ImGui::EndMenu();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    bool ProjectX_ImGui_MenuItem(const char* label, const char* shortcut, bool selected, bool enabled) {
        if (!label) {
            return false;
        }
        
        try {
            return ImGui::MenuItem(label, shortcut, selected, enabled);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_MenuItem", "Unknown exception during menu item");
            return false;
        }
    }
    
    // ===== Child Window Functions =====
    
    bool ProjectX_ImGui_BeginChild(const char* str_id, float size_x, float size_y, int child_flags, int window_flags) {
        if (!str_id) {
            return false;
        }
        
        try {
            return ImGui::BeginChild(str_id, ImVec2(size_x, size_y), child_flags, window_flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginChild", "Unknown exception during begin child");
            return false;
        }
    }
    
    void ProjectX_ImGui_EndChild() {
        try {
            ImGui::EndChild();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    // ===== Color Functions =====
    
    bool ProjectX_ImGui_ColorEdit3(const char* label, float col[3], int flags) {
        if (!label || !col) {
            return false;
        }
        
        try {
            return ImGui::ColorEdit3(label, col, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_ColorEdit3", "Unknown exception during color edit 3");
            return false;
        }
    }
    
    bool ProjectX_ImGui_ColorEdit4(const char* label, float col[4], int flags) {
        if (!label || !col) {
            return false;
        }
        
        try {
            return ImGui::ColorEdit4(label, col, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_ColorEdit4", "Unknown exception during color edit 4");
            return false;
        }
    }
    
    bool ProjectX_ImGui_ColorPicker3(const char* label, float col[3], int flags) {
        if (!label || !col) {
            return false;
        }
        
        try {
            return ImGui::ColorPicker3(label, col, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_ColorPicker3", "Unknown exception during color picker 3");
            return false;
        }
    }
    
    bool ProjectX_ImGui_ColorPicker4(const char* label, float col[4], int flags, const float* ref_col) {
        if (!label || !col) {
            return false;
        }
        
        try {
            return ImGui::ColorPicker4(label, col, flags, ref_col);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_ColorPicker4", "Unknown exception during color picker 4");
            return false;
        }
    }
    
    // ===== Tooltip Functions =====
    
    void ProjectX_ImGui_BeginTooltip() {
        try {
            ImGui::BeginTooltip();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    void ProjectX_ImGui_EndTooltip() {
        try {
            ImGui::EndTooltip();
        } catch (...) {
            // Silently handle errors
        }
    }
    
    void ProjectX_ImGui_SetTooltip(const char* text) {
        if (!text) {
            return;
        }
        
        try {
            ImGui::SetTooltip("%s", text);
        } catch (...) {
            // Silently handle errors
        }
    }
    
    // ===== Table Functions =====
    
    bool ProjectX_ImGui_BeginTable(const char* str_id, int columns, int flags, float outer_size_x, float outer_size_y, float inner_width) {
        if (!str_id || columns <= 0) {
            return false;
        }
        
        try {
            return ImGui::BeginTable(str_id, columns, flags, ImVec2(outer_size_x, outer_size_y), inner_width);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginTable", "Unknown exception during begin table");
            return false;
        }
    }
    
    void ProjectX_ImGui_EndTable() {
        try {
            ImGui::EndTable();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_EndTable", "Unknown exception during end table");
        }
    }
    
    void ProjectX_ImGui_TableNextRow(int row_flags, float min_row_height) {
        try {
            ImGui::TableNextRow(row_flags, min_row_height);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TableNextRow", "Unknown exception during table next row");
        }
    }
    
    bool ProjectX_ImGui_TableNextColumn() {
        try {
            return ImGui::TableNextColumn();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TableNextColumn", "Unknown exception during table next column");
            return false;
        }
    }
    
    bool ProjectX_ImGui_TableSetColumnIndex(int column_n) {
        if (column_n < 0) {
            return false;
        }
        
        try {
            return ImGui::TableSetColumnIndex(column_n);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TableSetColumnIndex", "Unknown exception during table set column index");
            return false;
        }
    }
    
    void ProjectX_ImGui_TableSetupColumn(const char* label, int flags, float init_width_or_weight, unsigned int user_id) {
        // Label can be null for unnamed columns
        try {
            ImGui::TableSetupColumn(label, flags, init_width_or_weight, user_id);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TableSetupColumn", "Unknown exception during table setup column");
        }
    }
    
    void ProjectX_ImGui_TableHeadersRow() {
        try {
            ImGui::TableHeadersRow();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TableHeadersRow", "Unknown exception during table headers row");
        }
    }
    
    void ProjectX_ImGui_TableHeader(const char* label) {
        if (!label) {
            return;
        }
        
        try {
            ImGui::TableHeader(label);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_TableHeader", "Unknown exception during table header");
        }
    }
    
    // ===== Drawing Functions =====
    
    void* ProjectX_ImGui_GetWindowDrawList() {
        try {
            return ImGui::GetWindowDrawList();
        } catch (...) {
            return nullptr;
        }
    }
    
    void* ProjectX_ImGui_GetBackgroundDrawList() {
        try {
            return ImGui::GetBackgroundDrawList();
        } catch (...) {
            return nullptr;
        }
    }
    
    void* ProjectX_ImGui_GetForegroundDrawList() {
        try {
            return ImGui::GetForegroundDrawList();
        } catch (...) {
            return nullptr;
        }
    }
    
    void ProjectX_ImGui_DrawList_AddLine(void* draw_list, float x1, float y1, float x2, float y2, unsigned int col, float thickness) {
        if (!draw_list) {
            return;
        }
        
        try {
            ((ImDrawList*)draw_list)->AddLine(ImVec2(x1, y1), ImVec2(x2, y2), col, thickness);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddLine", "Unknown exception during draw list add line");
        }
    }
    
    void ProjectX_ImGui_DrawList_AddRect(void* draw_list, float x1, float y1, float x2, float y2, unsigned int col, float rounding, int flags, float thickness) {
        if (!draw_list) {
            return;
        }
        
        try {
            ((ImDrawList*)draw_list)->AddRect(ImVec2(x1, y1), ImVec2(x2, y2), col, rounding, flags, thickness);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddRect", "Unknown exception during draw list add rect");
        }
    }
    
    void ProjectX_ImGui_DrawList_AddRectFilled(void* draw_list, float x1, float y1, float x2, float y2, unsigned int col, float rounding, int flags) {
        if (!draw_list) {
            return;
        }
        
        try {
            ((ImDrawList*)draw_list)->AddRectFilled(ImVec2(x1, y1), ImVec2(x2, y2), col, rounding, flags);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddRectFilled", "Unknown exception during draw list add rect filled");
        }
    }
    
    void ProjectX_ImGui_DrawList_AddCircle(void* draw_list, float center_x, float center_y, float radius, unsigned int col, int num_segments, float thickness) {
        if (!draw_list) {
            return;
        }
        
        try {
            ((ImDrawList*)draw_list)->AddCircle(ImVec2(center_x, center_y), radius, col, num_segments, thickness);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddCircle", "Unknown exception during draw list add circle");
        }
    }
    
    void ProjectX_ImGui_DrawList_AddCircleFilled(void* draw_list, float center_x, float center_y, float radius, unsigned int col, int num_segments) {
        if (!draw_list) {
            return;
        }
        
        try {
            ((ImDrawList*)draw_list)->AddCircleFilled(ImVec2(center_x, center_y), radius, col, num_segments);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddCircleFilled", "Unknown exception during draw list add circle filled");
        }
    }
    
    void ProjectX_ImGui_DrawList_AddText(void* draw_list, float x, float y, unsigned int col, const char* text) {
        if (!draw_list) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddText", "DrawList pointer is null");
            return;
        }
        
        if (!text) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddText", "Text pointer is null");
            return;
        }
        
        // Validate coordinates are reasonable
        if (x < -100000 || x > 100000 || y < -100000 || y > 100000) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddText", "Text coordinates are unreasonable");
            return;
        }
        
        try {
            ((ImDrawList*)draw_list)->AddText(ImVec2(x, y), col, text);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddText", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddText", "Unknown exception during text drawing");
        }
    }

    void ProjectX_ImGui_DrawList_AddImage(void* draw_list, int64_t texture_id, float x1, float y1, float x2, float y2, unsigned int col) {
        if (!draw_list) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddImage", "DrawList pointer is null");
            return;
        }

        try {
            ((ImDrawList*)draw_list)->AddImage(ProjectX_TextureRef(texture_id), ImVec2(x1, y1), ImVec2(x2, y2), ImVec2(0, 0), ImVec2(1, 1), col);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddImage", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddImage", "Unknown exception during image drawing");
        }
    }

    void ProjectX_ImGui_DrawList_AddConvexPolyFilled(void* draw_list, float* points, int num_points, unsigned int col) {
        if (!draw_list) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddConvexPolyFilled", "DrawList pointer is null");
            return;
        }
        
        if (!points) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddConvexPolyFilled", "Points pointer is null");
            return;
        }
        
        if (num_points < 3) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddConvexPolyFilled", "Need at least 3 points for polygon");
            return;
        }
        
        try {
            std::vector<ImVec2> imvec_points;
            imvec_points.reserve(num_points);
            for (int i = 0; i < num_points; i++) {
                imvec_points.emplace_back(points[i * 2], points[i * 2 + 1]);
            }
            ((ImDrawList*)draw_list)->AddConvexPolyFilled(imvec_points.data(), num_points, col);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddConvexPolyFilled", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddConvexPolyFilled", "Unknown exception during polygon drawing");
        }
    }

    void ProjectX_ImGui_DrawList_AddPolyline(void* draw_list, float* points, int num_points, unsigned int col, int flags, float thickness) {
        if (!draw_list) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddPolyline", "DrawList pointer is null");
            return;
        }
        
        if (!points) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddPolyline", "Points pointer is null");
            return;
        }
        
        if (num_points < 2) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddPolyline", "Need at least 2 points for polyline");
            return;
        }
        
        try {
            std::vector<ImVec2> imvec_points;
            imvec_points.reserve(num_points);
            for (int i = 0; i < num_points; i++) {
                imvec_points.emplace_back(points[i * 2], points[i * 2 + 1]);
            }
            ((ImDrawList*)draw_list)->AddPolyline(imvec_points.data(), num_points, col, (ImDrawFlags)flags, thickness);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddPolyline", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_DrawList_AddPolyline", "Unknown exception during polyline drawing");
        }
    }
    
    // ===== Utility Functions =====
    
    bool ProjectX_ImGui_IsItemHovered(int flags) {
        try {
            return ImGui::IsItemHovered(flags);
        } catch (...) {
            return false;
        }
    }
    
    bool ProjectX_ImGui_IsItemClicked(int mouse_button) {
        // Validate mouse button range (ImGui typically supports 0-4)
        if (mouse_button < 0 || mouse_button > 4) {
            imgui_log_error("ProjectX_ImGui_IsItemClicked", "Mouse button index out of range (0-4)");
            return false;
        }
        
        try {
            return ImGui::IsItemClicked(mouse_button);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_IsItemClicked", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_IsItemClicked", "Unknown exception during item click check");
            return false;
        }
    }
    
    void ProjectX_ImGui_GetIO(float* mouse_x, float* mouse_y, float* framerate, float* delta_time) {
        try {
            ImGuiIO& io = ImGui::GetIO();
            
            // Safe pointer writes
            if (mouse_x) *mouse_x = io.MousePos.x;
            if (mouse_y) *mouse_y = io.MousePos.y;
            if (framerate) *framerate = io.Framerate;
            if (delta_time) *delta_time = io.DeltaTime;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_GetIO", "Unknown exception during io");
            if (mouse_x) *mouse_x = 0.0f;
            if (mouse_y) *mouse_y = 0.0f;
            if (framerate) *framerate = 60.0f;
            if (delta_time) *delta_time = 0.016f;
        }
    }
    
    void ProjectX_ImGui_GetContentRegionAvail(float* size_x, float* size_y) {
        try {
            ImVec2 avail = ImGui::GetContentRegionAvail();
            if (size_x) *size_x = avail.x;
            if (size_y) *size_y = avail.y;
        } catch (...) {
            if (size_x) *size_x = 0.0f;
            if (size_y) *size_y = 0.0f;
        }
    }
    
    // ===== Scrolling Helpers =====
    float ProjectX_ImGui_GetScrollY() {
        try {
            return ImGui::GetScrollY();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_GetScrollY", "Unknown exception during scroll y");
            return 0.0f;
        }
    }

    float ProjectX_ImGui_GetScrollMaxY() {
        try {
            return ImGui::GetScrollMaxY();
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_GetScrollMaxY", "Unknown exception during scroll max y");
            return 0.0f;
        }
    }

    void ProjectX_ImGui_SetScrollHereY(float ratio) {
        try {
            ImGui::SetScrollHereY(ratio);
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_SetScrollHereY", "Unknown exception during scroll here y");
        }
    }

    void ProjectX_ImGui_CalcTextSize(const char* text, float* size_x, float* size_y, bool hide_text_after_double_hash, float wrap_width) {
        if (!text) {
            if (size_x) *size_x = 0.0f;
            if (size_y) *size_y = 0.0f;
            return;
        }
        
        try {
            ImVec2 text_size = ImGui::CalcTextSize(text, nullptr, hide_text_after_double_hash, wrap_width);
            if (size_x) *size_x = text_size.x;
            if (size_y) *size_y = text_size.y;
        } catch (...) {
            if (size_x) *size_x = 0.0f;
            if (size_y) *size_y = 0.0f;
        }
    }
    
    void ProjectX_ImGui_GetCursorPos(float* pos_x, float* pos_y) {
        try {
            ImVec2 pos = ImGui::GetCursorPos();
            if (pos_x) *pos_x = pos.x;
            if (pos_y) *pos_y = pos.y;
        } catch (...) {
            if (pos_x) *pos_x = 0.0f;
            if (pos_y) *pos_y = 0.0f;
        }
    }
    
    void ProjectX_ImGui_SetCursorPos(float pos_x, float pos_y) {
        try {
            ImGui::SetCursorPos(ImVec2(pos_x, pos_y));
        } catch (...) {
            // Silently handle errors
        }
    }
    
    void ProjectX_ImGui_GetCursorScreenPos(float* pos_x, float* pos_y) {
        try {
            ImVec2 pos = ImGui::GetCursorScreenPos();
            if (pos_x) *pos_x = pos.x;
            if (pos_y) *pos_y = pos.y;
        } catch (...) {
            if (pos_x) *pos_x = 0.0f;
            if (pos_y) *pos_y = 0.0f;
        }
    }
    
    void ProjectX_ImGui_SetCursorScreenPos(float pos_x, float pos_y) {
        try {
            ImGui::SetCursorScreenPos(ImVec2(pos_x, pos_y));
        } catch (...) {
            // Silently handle errors
        }
    }
    
    void ProjectX_ImGui_Dummy(float size_x, float size_y) {
        try {
            ImGui::Dummy(ImVec2(size_x, size_y));
        } catch (...) {
            // Silently handle errors
        }
    }

    // ===== Popup Functions =====
    
    void ProjectX_ImGui_OpenPopup(const char* str_id, int popup_flags) {
        if (!str_id) {
            imgui_log_error("ProjectX_ImGui_OpenPopup", "Popup ID is null");
            return;
        }
        
        try {
            ImGui::OpenPopup(str_id, popup_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_OpenPopup", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_OpenPopup", "Unknown exception during popup open");
        }
    }
    
    void ProjectX_ImGui_CloseCurrentPopup() {
        try {
            ImGui::CloseCurrentPopup();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_CloseCurrentPopup", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_CloseCurrentPopup", "Unknown exception during popup close");
        }
    }
    
    bool ProjectX_ImGui_BeginPopup(const char* str_id, int window_flags) {
        if (!str_id) {
            imgui_log_error("ProjectX_ImGui_BeginPopup", "Popup ID is null");
            return false;
        }
        
        try {
            return ImGui::BeginPopup(str_id, window_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_BeginPopup", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginPopup", "Unknown exception during popup begin");
            return false;
        }
    }
    
    void ProjectX_ImGui_EndPopup() {
        try {
            ImGui::EndPopup();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_EndPopup", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_EndPopup", "Unknown exception during popup end");
        }
    }
    
    bool ProjectX_ImGui_IsPopupOpen(const char* str_id, int popup_flags) {
        if (!str_id) {
            imgui_log_error("ProjectX_ImGui_IsPopupOpen", "Popup ID is null");
            return false;
        }
        
        try {
            return ImGui::IsPopupOpen(str_id, popup_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_IsPopupOpen", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_IsPopupOpen", "Unknown exception during popup query");
            return false;
        }
    }
    
    bool ProjectX_ImGui_BeginPopupModal(const char* name, bool* p_open, int window_flags) {
        if (!name) {
            imgui_log_error("ProjectX_ImGui_BeginPopupModal", "Modal name is null");
            return false;
        }
        
        try {
            return ImGui::BeginPopupModal(name, p_open, window_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_BeginPopupModal", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginPopupModal", "Unknown exception during modal begin");
            return false;
        }
    }
    
    void ProjectX_ImGui_OpenPopupOnItemClick(const char* str_id, int popup_flags) {
        // str_id can be null for default naming
        try {
            ImGui::OpenPopupOnItemClick(str_id, popup_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_OpenPopupOnItemClick", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_OpenPopupOnItemClick", "Unknown exception during popup on item click");
        }
    }
    
    bool ProjectX_ImGui_BeginPopupContextItem(const char* str_id, int popup_flags) {
        // str_id can be null for default naming
        try {
            return ImGui::BeginPopupContextItem(str_id, popup_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_BeginPopupContextItem", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginPopupContextItem", "Unknown exception during context item popup");
            return false;
        }
    }
    
    bool ProjectX_ImGui_BeginPopupContextWindow(const char* str_id, int popup_flags) {
        // str_id can be null for default naming
        try {
            return ImGui::BeginPopupContextWindow(str_id, popup_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_BeginPopupContextWindow", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginPopupContextWindow", "Unknown exception during context window popup");
            return false;
        }
    }
    
    bool ProjectX_ImGui_BeginPopupContextVoid(const char* str_id, int popup_flags) {
        // str_id can be null for default naming
        try {
            return ImGui::BeginPopupContextVoid(str_id, popup_flags);
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_BeginPopupContextVoid", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_BeginPopupContextVoid", "Unknown exception during context void popup");
            return false;
        }
    }
    
    // ===== Display / IO Helpers =====
    void ProjectX_ImGui_GetDisplaySize(float* out_width, float* out_height) {
        // Prefer ImGui IO if context is available and backend has set DisplaySize this frame
        if (ImGui::GetCurrentContext()) {
            ImGuiIO& io = ImGui::GetIO();
            if (out_width)  *out_width  = (io.DisplaySize.x > 0.0f) ? io.DisplaySize.x : 0.0f;
            if (out_height) *out_height = (io.DisplaySize.y > 0.0f) ? io.DisplaySize.y : 0.0f;
            if ((out_width ? *out_width : 1.0f) > 0.0f && (out_height ? *out_height : 1.0f) > 0.0f)
                return;
        }

        // Fallback: query primary display size from SDL
        if (out_width)  *out_width = 1280.0f;
        if (out_height) *out_height = 720.0f;
        int w = 0, h = 0;
        projectx::imgui_backend::desktop_size(&w, &h);
        if (w > 0 && out_width)  *out_width  = (float)w;
        if (h > 0 && out_height) *out_height = (float)h;
    }

    // Missing function implementations
    void ProjectX_ImGui_ProgressBar(float fraction, float size_x, float size_y, const char* overlay) {
        ImVec2 size(size_x, size_y);
        ImGui::ProgressBar(fraction, size, overlay && overlay[0] ? overlay : nullptr);
    }

    bool ProjectX_ImGui_BeginListBox(const char* label, float size_x, float size_y) {
        ImVec2 size(size_x, size_y);
        return ImGui::BeginListBox(label, size);
    }

    void ProjectX_ImGui_EndListBox() {
        ImGui::EndListBox();
    }

    void ProjectX_ImGui_TreePush(const char* str_id) {
        ImGui::TreePush(str_id);
    }

    bool ProjectX_ImGui_BeginTabBar(const char* str_id, int flags) {
        return ImGui::BeginTabBar(str_id, flags);
    }

    void ProjectX_ImGui_EndTabBar() {
        ImGui::EndTabBar();
    }

    bool ProjectX_ImGui_BeginTabItem(const char* label, bool* p_open, int flags) {
        return ImGui::BeginTabItem(label, p_open, flags);
    }

    void ProjectX_ImGui_EndTabItem() {
        ImGui::EndTabItem();
    }

    bool ProjectX_ImGui_TabItemButton(const char* label, int flags) {
        return ImGui::TabItemButton(label, flags);
    }

    void ProjectX_ImGui_Image(int64_t texture_id, float size_x, float size_y, float uv0_x, float uv0_y, float uv1_x, float uv1_y, unsigned int tint_col, unsigned int border_col) {
        ImVec2 size(size_x, size_y);
        ImVec2 uv0(uv0_x, uv0_y);
        ImVec2 uv1(uv1_x, uv1_y);
        ImGui::Image(ProjectX_TextureRef(texture_id), size, uv0, uv1, ImColor(tint_col), ImColor(border_col));
    }

    bool ProjectX_ImGui_ImageButton(int64_t texture_id, float size_x, float size_y, float uv0_x, float uv0_y, float uv1_x, float uv1_y, int frame_padding, unsigned int bg_col, unsigned int tint_col) {
        ImVec2 size(size_x, size_y);
        ImVec2 uv0(uv0_x, uv0_y);
        ImVec2 uv1(uv1_x, uv1_y);
        ImVec4 bg_color = ImColor(bg_col);
        ImVec4 tint_color = ImColor(tint_col);
        return ImGui::ImageButton("", ProjectX_TextureRef(texture_id), size, uv0, uv1, bg_color, tint_color);
    }

    // Create a texture from tightly packed RGBA8 pixel data provided by the JVM.
    // Pixels must be width*height*4 bytes in RGBA order, row-major, with no padding.
    // Handles are 64-bit: a GL texture name on OpenGL, an ImTextureData pointer on Vulkan.
    int64_t ProjectX_ImGui_CreateTextureFromRGBA(const void* pixels, int width, int height) {
        if (!pixels || width <= 0 || height <= 0) {
            imgui_log_error("ProjectX_ImGui_CreateTextureFromRGBA", "Invalid arguments");
            return 0;
        }

        // On the render thread the renderer can take it inline: the game's GL context is already
        // current on OpenGL, and ImGui's texture list belongs to this thread on Vulkan.
        if (g_renderer == OverlayRenderer::Vulkan && t_is_render_thread) {
            return ProjectX_CreateTextureNow(pixels, width, height);
        }
        if (g_renderer != OverlayRenderer::Vulkan && projectx::imgui_backend::has_current_gl_context()) {
            return (int64_t)ProjectX_GL_CreateTextureNow(pixels, width, height);
        }

        // Off the render thread (e.g. eager UI init): defer to the next frame and
        // block until the render thread uploads it. Never touch EGL/GL from here.
        // The caller's pixel buffer stays alive for the duration of this blocking call.
        PendingTexCreate req{pixels, width, height, 0, false};
        std::unique_lock<std::mutex> lock(g_gl_queue_mutex);
        g_pending_tex_creates.push_back(&req);
        if (!g_gl_create_cv.wait_for(lock, std::chrono::seconds(5), [&] { return req.done; })) {
            g_pending_tex_creates.erase(
                std::remove(g_pending_tex_creates.begin(), g_pending_tex_creates.end(), &req),
                g_pending_tex_creates.end());
            imgui_log_error("ProjectX_ImGui_CreateTextureFromRGBA",
                            "Timed out waiting for render thread to upload texture");
            return 0;
        }
        return req.result;
    }

    // Replace every pixel of a texture made by ProjectX_ImGui_CreateTextureFromRGBA, keeping its handle.
    // Render thread only: the GL path needs the game's context current, and the Vulkan path writes into
    // ImGui's texture list. The size must match the texture's; a mismatch is refused rather than resized.
    bool ProjectX_ImGui_UpdateTextureRGBA(int64_t texture_id, const void* pixels, int width, int height) {
        if (texture_id == 0 || !pixels || width <= 0 || height <= 0) {
            imgui_log_error("ProjectX_ImGui_UpdateTextureRGBA", "Invalid arguments");
            return false;
        }
        if (g_renderer == OverlayRenderer::Vulkan) {
            if (!t_is_render_thread) {
                imgui_log_error("ProjectX_ImGui_UpdateTextureRGBA", "Called off the render thread");
                return false;
            }
            ImTextureData* tex = (ImTextureData*)(intptr_t)texture_id;
            if (tex->Width != width || tex->Height != height || tex->WantDestroyNextFrame ||
                tex->Status == ImTextureStatus_WantDestroy || tex->Status == ImTextureStatus_Destroyed) {
                return false;
            }
            std::memcpy(tex->GetPixels(), pixels, (size_t)width * (size_t)height * 4);
            ImTextureDataQueueUpload(tex, 0, 0, width, height);
            return true;
        }
        if (!projectx::imgui_backend::has_current_gl_context()) {
            imgui_log_error("ProjectX_ImGui_UpdateTextureRGBA", "No GL context current");
            return false;
        }
        GLint prev_active_tex = 0, prev_tex_binding = 0, prev_unpack_align = 0;
        glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active_tex);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex_binding);
        glGetIntegerv(GL_UNPACK_ALIGNMENT, &prev_unpack_align);
        projectx::imgui_backend::active_texture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, (GLuint)texture_id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glPixelStorei(GL_UNPACK_ALIGNMENT, prev_unpack_align);
        glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex_binding);
        projectx::imgui_backend::active_texture((GLenum)prev_active_tex);
        return true;
    }

    void ProjectX_ImGui_DestroyTexture(int64_t texture_id) {
        if (texture_id == 0) return;
        // Called from any thread (notably the JVM Cleaner during texture GC).
        // Never touch the renderer here: defer the delete to the render thread.
        std::lock_guard<std::mutex> lock(g_gl_queue_mutex);
        if (g_renderer == OverlayRenderer::Vulkan) {
            g_pending_user_deletes.push_back((ImTextureData*)(intptr_t)texture_id);
        } else {
            g_pending_tex_deletes.push_back((GLuint)texture_id);
        }
    }

    bool ProjectX_ImGui_DragFloat(const char* label, float* v, float v_speed, float v_min, float v_max, const char* format, int flags) {
        return ImGui::DragFloat(label, v, v_speed, v_min, v_max, format, flags);
    }

    bool ProjectX_ImGui_DragInt(const char* label, int* v, float v_speed, int v_min, int v_max, const char* format, int flags) {
        return ImGui::DragInt(label, v, v_speed, v_min, v_max, format, flags);
    }

    bool ProjectX_ImGui_InputTextMultiline(const char* label, char* buf, int buf_size, float size_x, float size_y, int flags) {
        ImVec2 size(size_x, size_y);
        return ImGui::InputTextMultiline(label, buf, buf_size, size, flags);
    }

    bool ProjectX_ImGui_InputFloat2(const char* label, float v[2], const char* format, int flags) {
        return ImGui::InputFloat2(label, v, format, flags);
    }

    bool ProjectX_ImGui_InputFloat3(const char* label, float v[3], const char* format, int flags) {
        return ImGui::InputFloat3(label, v, format, flags);
    }

    bool ProjectX_ImGui_InputFloat4(const char* label, float v[4], const char* format, int flags) {
        return ImGui::InputFloat4(label, v, format, flags);
    }

    void ProjectX_ImGui_BeginGroup() {
        ImGui::BeginGroup();
    }

    void ProjectX_ImGui_EndGroup() {
        ImGui::EndGroup();
    }

    void ProjectX_ImGui_Indent(float indent_w) {
        ImGui::Indent(indent_w);
    }

    void ProjectX_ImGui_Unindent(float indent_w) {
        ImGui::Unindent(indent_w);
    }

    void ProjectX_ImGui_SetNextItemWidth(float item_width) {
        ImGui::SetNextItemWidth(item_width);
    }

    void ProjectX_ImGui_SetCursorPosX(float local_x) {
        ImGui::SetCursorPosX(local_x);
    }

    void ProjectX_ImGui_SetCursorPosY(float local_y) {
        ImGui::SetCursorPosY(local_y);
    }

    void ProjectX_ImGui_AlignTextToFramePadding() {
        ImGui::AlignTextToFramePadding();
    }

    void ProjectX_ImGui_Columns(int count, const char* id, bool border) {
        ImGui::Columns(count, id, border);
    }

    void ProjectX_ImGui_NextColumn() {
        ImGui::NextColumn();
    }

    void ProjectX_ImGui_SetColumnWidth(int column_index, float width) {
        ImGui::SetColumnWidth(column_index, width);
    }

    float ProjectX_ImGui_GetColumnWidth(int column_index) {
        return ImGui::GetColumnWidth(column_index);
    }

    void ProjectX_ImGui_PushStyleVarFloat(int idx, float val) {
        ImGui::PushStyleVar(idx, val);
    }

    void ProjectX_ImGui_PushStyleVarVec2(int idx, float x, float y) {
        ImGui::PushStyleVar(idx, ImVec2(x, y));
    }

    void ProjectX_ImGui_PopStyleVar(int count) {
        ImGui::PopStyleVar(count);
    }

    void ProjectX_ImGui_PushStyleColor(int idx, unsigned int col) {
        ImGui::PushStyleColor((ImGuiCol)idx, (ImU32)col);
    }

    void ProjectX_ImGui_PopStyleColor(int count) {
        ImGui::PopStyleColor(count);
    }

    void ProjectX_ImGui_PushItemWidth(float item_width) {
        ImGui::PushItemWidth(item_width);
    }

    void ProjectX_ImGui_PopItemWidth() {
        ImGui::PopItemWidth();
    }

    float ProjectX_ImGui_GetItemRectMinX() {
        return ImGui::GetItemRectMin().x;
    }

    float ProjectX_ImGui_GetItemRectMinY() {
        return ImGui::GetItemRectMin().y;
    }

    float ProjectX_ImGui_GetItemRectMaxX() {
        return ImGui::GetItemRectMax().x;
    }

    float ProjectX_ImGui_GetItemRectMaxY() {
        return ImGui::GetItemRectMax().y;
    }

    float ProjectX_ImGui_GetItemRectSizeX() {
        return ImGui::GetItemRectSize().x;
    }

    float ProjectX_ImGui_GetItemRectSizeY() {
        return ImGui::GetItemRectSize().y;
    }

    bool ProjectX_ImGui_IsItemActive() {
        return ImGui::IsItemActive();
    }

    bool ProjectX_ImGui_IsItemFocused() {
        return ImGui::IsItemFocused();
    }

    bool ProjectX_ImGui_IsItemVisible() {
        return ImGui::IsItemVisible();
    }

    float ProjectX_ImGui_GetWindowPosX() {
        return ImGui::GetWindowPos().x;
    }

    float ProjectX_ImGui_GetWindowPosY() {
        return ImGui::GetWindowPos().y;
    }

    float ProjectX_ImGui_GetWindowSizeX() {
        return ImGui::GetWindowSize().x;
    }

    float ProjectX_ImGui_GetWindowSizeY() {
        return ImGui::GetWindowSize().y;
    }

    float ProjectX_ImGui_GetMousePosX() {
        return ImGui::GetMousePos().x;
    }

    float ProjectX_ImGui_GetMousePosY() {
        return ImGui::GetMousePos().y;
    }

    bool ProjectX_ImGui_IsMouseDown(int button) {
        return ImGui::IsMouseDown(button);
    }

    bool ProjectX_ImGui_IsMouseClicked(int button, bool repeat) {
        return ImGui::IsMouseClicked(button, repeat);
    }

    bool ProjectX_ImGui_IsMouseDoubleClicked(int button) {
        return ImGui::IsMouseDoubleClicked(button);
    }

    // ===== Font Functions =====
    
    bool ProjectX_ImGui_AddFontFromFile(const char* filename, float size_pixels) {
        if (!filename || size_pixels <= 0.0f) {
            imgui_log_error("ProjectX_ImGui_AddFontFromFile", "Invalid parameters");
            return false;
        }
        
        try {
            ImGuiIO& io = ImGui::GetIO();
            ImFont* font = io.Fonts->AddFontFromFileTTF(filename, size_pixels);
            if (!font) {
                imgui_log_error("ProjectX_ImGui_AddFontFromFile", "Failed to load font");
                return false;
            }
            return true;
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_AddFontFromFile", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_AddFontFromFile", "Unknown exception during font loading");
            return false;
        }
    }
    
    bool ProjectX_ImGui_AddDefaultFont(float size_pixels) {
        if (size_pixels <= 0.0f) {
            size_pixels = 13.0f; // Default size
        }
        
        try {
            ImGuiIO& io = ImGui::GetIO();
            ImFontConfig config;
            config.SizePixels = size_pixels;
            ImFont* font = io.Fonts->AddFontDefault(&config);
            return font != nullptr;
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_AddDefaultFont", e.what());
            return false;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_AddDefaultFont", "Unknown exception during default font creation");
            return false;
        }
    }
    
    void ProjectX_ImGui_PushFont(int font_index) {
        try {
            ImGuiIO& io = ImGui::GetIO();
            if (font_index >= 0 && font_index < io.Fonts->Fonts.Size) {
                ImGui::PushFont(io.Fonts->Fonts[font_index]);
            }
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_PushFont", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_PushFont", "Unknown exception during font push");
        }
    }
    
    void ProjectX_ImGui_PopFont() {
        try {
            ImGui::PopFont();
        } catch (const std::exception& e) {
            imgui_log_error("ProjectX_ImGui_PopFont", e.what());
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_PopFont", "Unknown exception during font pop");
        }
    }
    
    int ProjectX_ImGui_GetFontCount() {
        try {
            ImGuiIO& io = ImGui::GetIO();
            return io.Fonts->Fonts.Size;
        } catch (...) {
            imgui_log_error("ProjectX_ImGui_GetFontCount", "Unknown exception during font count retrieval");
            return 0;
        }
    }
    
    bool ProjectX_ImGui_BuildFonts() {
        // Modern ImGui backends handle font atlas building automatically.
        // Manual Build() calls are no longer needed and can cause errors.
        // This function is kept for API compatibility but now returns true
        // since the backend will handle font building when needed.
        return true;
    }
}
