#include <SDL2/SDL.h>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <dlfcn.h>
#include "imgui.h"
#include "imgui_impl_sdl2.h"
#include "funchook.h"

// External log file from bootstrap
extern FILE *log_file;

// External logging functions from projectx_imgui.cpp
extern void imgui_log_message(const char *format, ...);
extern void imgui_log_error(const char* function_name, const char* error_msg);

// External frame state and event queueing from projectx_imgui.cpp
extern "C" {
    bool ProjectX_ImGui_IsFrameInProgress();
    void ProjectX_ImGui_QueueEvent(void* event);
}

// Funchook static variables
static funchook_t *sdl_hook = nullptr;
static int (*original_SDL_PollEvent)(SDL_Event* event) = nullptr;

extern "C" {
    // SDL_Event is a union that's 56 bytes on 64-bit systems
    static const size_t SDL_EVENT_SIZE = 56;
    
    // Function pointer type for the original SDL_PollEvent
    typedef int (*SDL_PollEvent_t)(SDL_Event* event);
    
    static bool isKeyboardEvent(int eventType) {
        return eventType == SDL_KEYDOWN || eventType == SDL_KEYUP || 
               eventType == SDL_TEXTEDITING || eventType == SDL_TEXTINPUT;
    }
    
    static bool isMouseEvent(int eventType) {
        return eventType == SDL_MOUSEMOTION || eventType == SDL_MOUSEBUTTONDOWN || 
               eventType == SDL_MOUSEBUTTONUP || eventType == SDL_MOUSEWHEEL;
    }
    
    // Validate SDL event type is within valid range
    static bool isValidSDLEvent(const SDL_Event* event) {
        if (!event) return false;
        
        // Check if event type is within SDL's valid range
        if (event->type < SDL_FIRSTEVENT || event->type > SDL_LASTEVENT) {
            return false;
        }
        
        // Additional basic validation for common event types
        switch (event->type) {
            case SDL_WINDOWEVENT:
                // Window events should have valid window ID (non-zero)
                return event->window.windowID != 0;
            case SDL_KEYDOWN:
            case SDL_KEYUP:
                // Key events should have valid scancode
                return event->key.keysym.scancode != SDL_SCANCODE_UNKNOWN;
            case SDL_MOUSEBUTTONDOWN:
            case SDL_MOUSEBUTTONUP:
                // Mouse button events should have valid button
                return event->button.button > 0 && event->button.button <= 32;
            default:
                // For other event types, basic range check is sufficient
                return true;
        }
    }
    
    // Hooked SDL_PollEvent function
    int ProjectX_SDL_PollEvent_Hook(SDL_Event* event) {
        // Validate event pointer - null check
        if (!event) {
            imgui_log_error("ProjectX_SDL_PollEvent_Hook", "Event pointer is null");
            return 0;
        }
        
        if (!original_SDL_PollEvent) {
            imgui_log_error("ProjectX_SDL_PollEvent_Hook", "Original SDL_PollEvent not set");
            return 0;
        }
        
        // Drain captured events while returning only the first non-captured event to the game
        int iterations = 0;
        while (true) {
            iterations++;
            if (iterations > 256) {
                // Safety break to avoid infinite loops in extreme cases
                imgui_log_message("SDL_PollEvent hook: safety break at 256 iterations\n");
                return 0;
            }
            
            int gotEvent = original_SDL_PollEvent(event);
            if (gotEvent == 0) {
                // No more events in queue
                return 0;
            }
            
            // Validate SDL event before processing
            if (!isValidSDLEvent(event)) {
                imgui_log_message("SDL_PollEvent hook: skipping invalid event type %u\n", event->type);
                continue; // Skip invalid events, keep polling
            }
            
            // Only process events through ImGui if context and backend are initialized
            bool imgui_initialized = ImGui::GetCurrentContext() != nullptr;
            bool capture_keyboard = false;
            bool capture_mouse = false;
            
            if (imgui_initialized) {
                // Check if ImGui IO has backend platform data (indicates SDL2 backend is initialized)
                ImGuiIO& io = ImGui::GetIO();
                if (io.BackendPlatformUserData != nullptr) {
                    // Check if it's safe to process events (not during frame processing)
                    if (ProjectX_ImGui_IsFrameInProgress()) {
                        // Queue event for later processing
                        ProjectX_ImGui_QueueEvent(event);
                    } else {
                        // Safe to process immediately - but only if event is valid
                        ImGui_ImplSDL2_ProcessEvent(event);
                    }
                    
                    // Check if ImGui wants to capture input
                    capture_keyboard = io.WantCaptureKeyboard;
                    capture_mouse = io.WantCaptureMouse;
                }
            }
            
            int eventType = event->type;
            
            bool isKbdEvent = isKeyboardEvent(eventType);
            bool isMouseEvt = isMouseEvent(eventType);
            
            // Drop captured events (don't return them to the game); continue draining queue
            if ((isKbdEvent && capture_keyboard) || (isMouseEvt && capture_mouse)) {
                continue;
            }
            
            // Not captured: return this event to the game
            return gotEvent;
        }
    }
    
    // Initialize SDL_PollEvent hook using funchook
    void ProjectX_InitializeSDLHook() {
        imgui_log_message("Initializing SDL_PollEvent hook\n");
        
        // Get SDL_PollEvent address
        void* sdl_pollevent_addr = dlsym(RTLD_DEFAULT, "SDL_PollEvent");
        if (!sdl_pollevent_addr) {
            imgui_log_error("ProjectX_InitializeSDLHook", "Failed to find SDL_PollEvent symbol");
            return;
        }
        
        imgui_log_message("Found SDL_PollEvent at address: %p\n", sdl_pollevent_addr);
        
        // Create funchook instance
        sdl_hook = funchook_create();
        if (!sdl_hook) {
            imgui_log_error("ProjectX_InitializeSDLHook", "Failed to create funchook instance");
            return;
        }
        
        // Set original function pointer
        original_SDL_PollEvent = (int (*)(SDL_Event*))sdl_pollevent_addr;
        
        // Prepare hook
        void* hook_target = sdl_pollevent_addr;
        int result = funchook_prepare(sdl_hook, &hook_target, (void*)ProjectX_SDL_PollEvent_Hook);
        if (result != FUNCHOOK_ERROR_SUCCESS) {
            imgui_log_error("ProjectX_InitializeSDLHook", funchook_error_message(sdl_hook));
            funchook_destroy(sdl_hook);
            sdl_hook = nullptr;
            return;
        }
        
        // Update trampoline pointer
        original_SDL_PollEvent = (int (*)(SDL_Event*))hook_target;
        
        // Install hook
        result = funchook_install(sdl_hook, 0);
        if (result != FUNCHOOK_ERROR_SUCCESS) {
            imgui_log_error("ProjectX_InitializeSDLHook", funchook_error_message(sdl_hook));
            funchook_destroy(sdl_hook);
            sdl_hook = nullptr;
            return;
        }
        
        imgui_log_message("SDL_PollEvent hook installed successfully. Trampoline: %p\n", (void*)original_SDL_PollEvent);
    }
    
    // Cleanup SDL_PollEvent hook
    void ProjectX_CleanupSDLHook() {
        if (sdl_hook) {
            imgui_log_message("Cleaning up SDL_PollEvent hook\n");
            funchook_uninstall(sdl_hook, 0);
            funchook_destroy(sdl_hook);
            sdl_hook = nullptr;
            original_SDL_PollEvent = nullptr;
        }
    }
}