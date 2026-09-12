cmake_minimum_required(VERSION 3.5.0)

# The OHOS SDK ships libc++ only; clang++ defaults to libstdc++ which is absent,
# so force libc++ before project() so the CMake compiler check can link.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -stdlib=libc++")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -stdlib=libc++")

project(librera_napi)

# libmupdf.so is cross-compiled separately (harmony/native/build_mupdf_harmony.sh)
# and staged into entry/libs/<abi>/ so hvigor packs it into the HAP; link against
# the staged copy here.
set(MUPDF_STAGED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/${OHOS_ARCH}/libmupdf.so)
set(MUPDF_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../Builder/mupdf-1.23.7/include)

if(NOT EXISTS ${MUPDF_STAGED_DIR})
    message(FATAL_ERROR "libmupdf.so not found at ${MUPDF_STAGED_DIR}; run harmony/native/build_mupdf_harmony.sh first")
endif()

add_library(mupdf SHARED IMPORTED)
set_target_properties(mupdf PROPERTIES IMPORTED_LOCATION ${MUPDF_STAGED_DIR})

# SMB/SFTP online-reading clients (libsmb2 / libssh2 with statically embedded
# OpenSSL) are cross-compiled separately and staged into entry/libs/<abi>/
# under their SONAMEs (libssh2.so.1 / libsmb2.so.1); see prebuilt/harmony/net/.
set(NETLIBS_STAGED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/${OHOS_ARCH})
set(NETLIBS_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../Builder)

foreach(NETLIB libssh2 libsmb2)
    if(NOT EXISTS ${NETLIBS_STAGED_DIR}/${NETLIB}.so.1)
        message(FATAL_ERROR "${NETLIB}.so.1 not found at ${NETLIBS_STAGED_DIR}; run the net-libs cross build first")
    endif()
endforeach()

add_library(libssh2 SHARED IMPORTED)
set_target_properties(libssh2 PROPERTIES IMPORTED_LOCATION ${NETLIBS_STAGED_DIR}/libssh2.so.1)
add_library(libsmb2 SHARED IMPORTED)
set_target_properties(libsmb2 PROPERTIES IMPORTED_LOCATION ${NETLIBS_STAGED_DIR}/libsmb2.so.1)

add_library(mupdf_napi SHARED mupdf_napi.cpp remote_net.cpp)

target_include_directories(mupdf_napi PRIVATE
    ${MUPDF_INCLUDE_DIR}
    ${NETLIBS_INCLUDE_DIR}/libsmb2/include
    ${NETLIBS_INCLUDE_DIR}/libssh2/include)
target_link_libraries(mupdf_napi PUBLIC mupdf)
target_link_libraries(mupdf_napi PUBLIC libssh2)
target_link_libraries(mupdf_napi PUBLIC libsmb2)
target_link_libraries(mupdf_napi PUBLIC libace_napi.z.so)
target_link_libraries(mupdf_napi PUBLIC libhilog_ndk.z.so)
