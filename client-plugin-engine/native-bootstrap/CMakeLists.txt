cmake_minimum_required(VERSION 3.5)
project(projectxbootstrap CXX)

set(CMAKE_C_COMPILER_ID clang)
set(CMAKE_CXX_COMPILER_ID clang++)

set(CMAKE_CXX_STANDARD 20)
# -g -gdwarf-4: clean, gdb-readable debug info (gdb was choking on corrupt/mismatched
# DWARF, which also breaks addr2line on the in-process crash backtrace).
# Uncompressed debug sections avoid the "wrong version in unit header" parse errors.
if (MSVC)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /W3 /Zi /EHsc")
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -Wall -g -gdwarf-4 -fno-omit-frame-pointer")
endif()

# Headers only — the JVM is resolved at runtime through dlopen/LoadLibrary and never linked. Left to
# itself find_package(JNI) fails a cross-build outright by demanding AWT/JVM link libraries for the
# target platform, so an explicitly supplied include path wins.
if (NOT JNI_INCLUDE_DIRS)
    find_package(JNI REQUIRED)
endif()
if (NOT WIN32)
    find_package(SDL2 REQUIRED)
endif()

include_directories(${JNI_INCLUDE_DIRS})

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(HAVE_FVISIBILITY_HIDDEN OFF CACHE BOOL "Have -fvisibility=hidden support" FORCE)
set(FUNCHOOK_BUILD_SHARED OFF CACHE BOOL "Build shared library" FORCE)
set(FUNCHOOK_BUILD_STATIC ON CACHE BOOL "Build static library" FORCE)

add_subdirectory(funchook)

# The client is SDL2/EGL on Linux and Win32/WGL on Windows, so the ImGui platform backend and our
# adapter for it are chosen together.
if (WIN32)
    set(PROJECTX_INPUT_PROCESSOR "")
    set(IMGUI_PLATFORM_BACKEND imgui/backends/imgui_impl_win32.cpp)
    set(PROJECTX_IMGUI_BACKEND imgui_backend_win32.cpp)
else()
    set(PROJECTX_INPUT_PROCESSOR projectx_imgui_inputprocessor.cpp)
    set(IMGUI_PLATFORM_BACKEND imgui/backends/imgui_impl_sdl2.cpp)
    set(PROJECTX_IMGUI_BACKEND imgui_backend_sdl2.cpp)
endif()

add_library(imgui-static STATIC
    imgui/imgui.cpp
    imgui/imgui_draw.cpp
    imgui/imgui_tables.cpp
    imgui/imgui_widgets.cpp
    imgui/imgui_demo.cpp
    imgui/backends/imgui_impl_opengl3.cpp
    ${IMGUI_PLATFORM_BACKEND}
)
target_include_directories(imgui-static PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/imgui
    ${CMAKE_CURRENT_SOURCE_DIR}/imgui/backends
)
if (WIN32)
    target_link_libraries(imgui-static PUBLIC opengl32 gdi32 dwmapi)
else()
    target_link_libraries(imgui-static PUBLIC EGL OpenGL SDL2::SDL2)
endif()

# The bootstrap core is platform-neutral; everything that differs between the POSIX and Windows
# builds of the client lives behind projectx_platform.h in exactly one of these two files.
if (WIN32)
    set(PROJECTX_PLATFORM_SOURCES platform/win32.cpp)
else()
    set(PROJECTX_PLATFORM_SOURCES platform/posix.cpp)
endif()

add_library(projectxbootstrap SHARED
    projectx_bootstrap.cpp
    ${PROJECTX_PLATFORM_SOURCES}
    projectx_imgui.cpp
    ${PROJECTX_IMGUI_BACKEND}
    ${PROJECTX_INPUT_PROCESSOR}
)

# MinGW would otherwise emit libprojectxbootstrap.dll, but the injector and the Gradle install task
# both look for the MSVC-style name.
if (WIN32)
    set_target_properties(projectxbootstrap PROPERTIES PREFIX "")
endif()

# Multi-config generators (Visual Studio) append <Config>/ to a target's output directory unless the
# matching per-config property is set too, leaving the library somewhere the Gradle install task does
# not look and a previous build's copy to be reinstalled in its place. CMAKE_CONFIGURATION_TYPES is
# empty on single-config generators, so the loop below is inert there.
set_target_properties(projectxbootstrap PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
foreach (config IN LISTS CMAKE_CONFIGURATION_TYPES)
    string(TOUPPER "${config}" config_name)
    set_target_properties(projectxbootstrap PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY_${config_name} "${CMAKE_BINARY_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_${config_name} "${CMAKE_BINARY_DIR}")
endforeach()

find_package(Threads REQUIRED)
if (WIN32)
    # dbghelp backs StackWalk64 in the crash handler; shell32/ole32 back the known-folder lookup.
    set(ADDITIONAL_LIBS ${CMAKE_THREAD_LIBS_INIT} dbghelp shell32 ole32 ws2_32)
else()
    set(ADDITIONAL_LIBS ${CMAKE_THREAD_LIBS_INIT} dl)
endif()

# Export dynamic symbols so backtrace_symbols_fd() in the crash handler resolves
# our own frames to function names instead of bare module+offset. The Windows crash handler resolves
# frames through dbghelp instead, so it needs no equivalent.
if (NOT WIN32)
    target_link_options(projectxbootstrap PRIVATE -rdynamic -Wl,--compress-debug-sections=none)
endif()

# The DLL is injected into a client that knows nothing about MinGW, so it cannot depend on
# libgcc_s_seh-1 / libstdc++-6 / libwinpthread-1 sitting next to rs2client.exe — LoadLibraryW would
# simply fail. Link the toolchain runtime in statically instead.
if (MINGW)
    target_link_options(projectxbootstrap PRIVATE
        -static-libgcc -static-libstdc++
        -Wl,-Bstatic -Wl,--whole-archive -lwinpthread -Wl,--no-whole-archive -Wl,-Bdynamic
        # The engine resolves funchook through a Panama SymbolLookup over this module. A DLL exports
        # only what is explicitly marked, so without this the lookup fails and no hook installs —
        # the ELF build gets the same reach from -rdynamic.
        -Wl,--export-all-symbols)
endif()

# MSVC has no --export-all-symbols, and the engine reaches every entry point here through a Panama
# SymbolLookup over this module, so anything left unexported is a runtime "Symbol not found".
# WINDOWS_EXPORT_ALL_SYMBOLS covers this target's own objects — the whole ProjectX_* surface — but it
# never scans the static libraries linked in, so funchook's API still has to be named. /EXPORT
# doubles as the reference that pulls those objects out of the archive; nothing on the C++ side calls
# funchook, so plain static linking drops them as unreferenced.
if (MSVC)
    set_target_properties(projectxbootstrap PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
    foreach (funchook_symbol funchook_create funchook_prepare funchook_install funchook_uninstall funchook_destroy)
        target_link_options(projectxbootstrap PRIVATE "/EXPORT:${funchook_symbol}")
    endforeach()
endif()

if ((UNIX AND NOT APPLE) OR MINGW)
    target_link_libraries(projectxbootstrap PRIVATE "-Wl,--whole-archive" funchook-static "-Wl,--no-whole-archive" imgui-static ${ADDITIONAL_LIBS})
else()
    target_link_libraries(projectxbootstrap PRIVATE funchook-static imgui-static ${ADDITIONAL_LIBS})
endif()
