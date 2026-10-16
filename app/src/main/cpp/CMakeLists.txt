cmake_minimum_required(VERSION 3.18.1)
project(airmirror C CXX)

set(CMAKE_C_STANDARD 11)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# ---------- libplist (vendored, 2.6.0) ----------
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC ${TP}/libplist/include
                                 PRIVATE ${TP}/libplist/src ${TP}/libplist/libcnary/include)
target_compile_definitions(plist PRIVATE PACKAGE_VERSION="2.6.0"
    HAVE_ATTRIBUTE_CONSTRUCTOR HAVE_STRNDUP HAVE_STRPTIME _GNU_SOURCE HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE)
target_compile_options(plist PRIVATE -w -fPIC)

# ---------- OpenSSL libcrypto ----------
if(ANDROID)
    find_package(openssl REQUIRED CONFIG)
    set(CRYPTO_LIB openssl::crypto)
else()
    find_package(OpenSSL REQUIRED)
    set(CRYPTO_LIB OpenSSL::Crypto)
endif()

# ---------- UxPlay AirPlay library (vendored) ----------
file(GLOB AIRPLAY_SRC
    ${TP}/uxplay/*.c
    ${TP}/uxplay/playfair/*.c
    ${TP}/uxplay/llhttp/*.c
    ${TP}/uxplay/mdnsd/*.c)
add_library(airplay STATIC ${AIRPLAY_SRC})
target_include_directories(airplay PUBLIC ${TP}/uxplay
    PRIVATE ${TP}/uxplay/playfair ${TP}/uxplay/llhttp ${TP}/uxplay/mdnsd)
target_compile_definitions(airplay PRIVATE
    STANDALONE TARGET_POSIX _LINUX _REENTRANT _LARGEFILE64_SOURCE
    NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(airplay PRIVATE -O2 -fPIC -w)
target_link_libraries(airplay PUBLIC plist ${CRYPTO_LIB} m)
if(NOT ANDROID)
    target_link_libraries(airplay PUBLIC pthread)
endif()

# ---------- JNI bridge ----------
if(ANDROID)
    add_library(airmirror SHARED airmirror_jni.cpp)
    target_link_libraries(airmirror airplay log)
else()
    # host build used only to compile-check the library
    add_executable(hostcheck hostcheck.c)
    target_link_libraries(hostcheck airplay)
endif()
