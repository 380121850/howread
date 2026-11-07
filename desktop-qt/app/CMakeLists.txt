# WIN32：GUI 子系统（-mwindows），避免启动时附加控制台黑框
enable_language(RC) # v0.90.0 W11：exe 内嵌图标（app.rc -> res/howread.ico）
set(CMAKE_AUTORCC ON) # v0.76.0 V1：icons.qrc 图标资源
add_executable(HowReadQt WIN32
    app.rc
    main.cpp hrpaths.cpp hrdb.cpp hrdb_m2.cpp hrdb_m3.cpp
    readerwindow.cpp librarywindow.cpp hrbcache.cpp hrwebdav.cpp hrsync.cpp
    readermark.cpp readerm4.cpp hrtts.cpp hrai.cpp
    hrcvt.cpp hrl10n.cpp hrssh.cpp hropds.cpp hrdict.cpp hrfetch.cpp hrsmb.cpp
    hrcred.cpp hrstats.cpp readerjson.cpp hricons.cpp hrtheme.cpp icons.qrc res.qrc)
if (HR_QTVER STREQUAL "6")
    target_link_libraries(HowReadQt PRIVATE Qt6::Widgets Qt6::Gui Qt6::Sql Qt6::Network mupdfqt uuid ole32)
else()
    target_link_libraries(HowReadQt PRIVATE Qt5::Widgets Qt5::Gui Qt5::Sql Qt5::Network mupdfqt uuid ole32)
    # MXE 静态 Qt5：平台/SQLite 插件目标显式接入（若配置提供）
    if (TARGET Qt5::QWindowsIntegrationPlugin)
        target_link_libraries(HowReadQt PRIVATE Qt5::QWindowsIntegrationPlugin)
    endif()
    if (TARGET Qt5::QSQLiteDriverPlugin)
        target_link_libraries(HowReadQt PRIVATE Qt5::QSQLiteDriverPlugin)
    endif()
endif()
target_include_directories(HowReadQt PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../engine-bridge)


# v0.60 批次二：SFTP（libssh2/WinCNG，MXE 交叉库；本机 Qt6 通道缺库时自动降级为无 SFTP）
find_library(HR_LIBSSH2 NAMES ssh2
    PATHS /docker/opt/mxe/usr/x86_64-w64-mingw32.static/lib)
if (HR_LIBSSH2)
    target_compile_definitions(HowReadQt PRIVATE HR_HAVE_LIBSSH2)
    target_include_directories(HowReadQt PRIVATE /docker/opt/mxe/usr/x86_64-w64-mingw32.static/include)
    find_library(HR_CRYPTO crypto PATHS /docker/opt/mxe/usr/x86_64-w64-mingw32.static/lib)
    target_link_libraries(HowReadQt PRIVATE ${HR_LIBSSH2} ${HR_CRYPTO} ws2_32 crypt32)
endif()
# v0.71.0：SMB（libsmb2 vendored 静态库，MXE 通道；本机 Qt6 通道缺库自动降级无 SMB）
# 注：MXE 工具链 FIND_ROOT_PATH=ONLY 会重根 find_library 的 PATHS——用 EXISTS 硬路径判定
set(HR_SMB2_DIR /docker/opt/librera/LibreraReader/Builder/libsmb2/build-mxe-win64/out)
if (EXISTS ${HR_SMB2_DIR}/lib/libsmb2.a)
    target_compile_definitions(HowReadQt PRIVATE HR_HAVE_LIBSMB2)
    target_include_directories(HowReadQt PRIVATE ${HR_SMB2_DIR}/include)
    target_link_libraries(HowReadQt PRIVATE ${HR_SMB2_DIR}/lib/libsmb2.a ws2_32)
endif()
# v0.76.0 V1：QtSvg（图标渲染，MXE 2026-11-01 编入）；本机 Qt6 通道缺库自动退化
set(HR_QTSVG_DIR /docker/opt/mxe/usr/x86_64-w64-mingw32.static/qt5)
if (EXISTS ${HR_QTSVG_DIR}/lib/libQt5Svg.a)
    target_compile_definitions(HowReadQt PRIVATE HR_HAVE_QTSVG)
    target_include_directories(HowReadQt PRIVATE ${HR_QTSVG_DIR}/include/QtSvg)
    target_link_libraries(HowReadQt PRIVATE ${HR_QTSVG_DIR}/lib/libQt5Svg.a)
endif()
# StarDict gunzip（工具链 zlib；本机通道走 find_package）
find_library(HR_ZLIB NAMES z zlib PATHS /docker/opt/mxe/usr/x86_64-w64-mingw32.static/lib)
if (NOT HR_ZLIB)
    find_package(ZLIB QUIET)
    if (ZLIB_FOUND)
        set(HR_ZLIB ${ZLIB_LIBRARIES})
        target_include_directories(HowReadQt PRIVATE ${ZLIB_INCLUDE_DIRS})
    endif()
endif()
if (HR_ZLIB)
    target_compile_definitions(HowReadQt PRIVATE HR_HAVE_ZLIB)
    target_link_libraries(HowReadQt PRIVATE ${HR_ZLIB})
endif()
