cmake_minimum_required(VERSION 3.22.1)
project(lyricsaubio C CXX)

file(GLOB AUBIO_SOURCES aubio/src/*.c aubio/src/spectral/*.c aubio/src/onset/*.c aubio/src/tempo/*.c aubio/src/temporal/*.c aubio/src/utils/*.c)

add_library(lyricsaubio SHARED aubio_jni.c ${AUBIO_SOURCES})

target_include_directories(lyricsaubio PRIVATE aubio/src)

target_compile_definitions(lyricsaubio PRIVATE HAVE_STDLIB_H=1 HAVE_STDIO_H=1 HAVE_MATH_H=1 HAVE_STRING_H=1 HAVE_ERRNO_H=1 HAVE_LIMITS_H=1 HAVE_STDARG_H=1 HAVE_C99_VARARGS_MACROS=1 HAVE_MEMCPY_HACKS=1 NDEBUG=1)

target_compile_options(lyricsaubio PRIVATE -O3 -w)
target_link_libraries(lyricsaubio m log)

add_library(lyricsaudio SHARED spotify-audio/bridge.c spotify-audio/epoch.c spotify-audio/resource.cpp spotify-audio/cache-copy.cpp spotify-audio/cache-file.cpp spotify-audio/cache-window.cpp spotify-audio/cache-song-cipher.cpp spotify-audio/cache-jni.cpp)
target_compile_features(lyricsaudio PRIVATE cxx_std_20)
target_link_options(lyricsaudio PRIVATE "-Wl,-Bsymbolic")
target_link_libraries(lyricsaudio dl log)

add_library(lyricsremoteaudio SHARED spotify-remote-audio/remote-jni.cpp spotify-remote-audio/remote-song-cipher.cpp)
target_compile_features(lyricsremoteaudio PRIVATE cxx_std_20)
target_link_options(lyricsremoteaudio PRIVATE "-Wl,-Bsymbolic")
target_link_libraries(lyricsremoteaudio dl log)
