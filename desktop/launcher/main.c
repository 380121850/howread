/* HowReadPro portable launcher (M6): spawns the bundled Temurin runtime
 * (runtime\bin\javaw.exe) with the app classpath and engine library path.
 * Everything is resolved relative to the launcher's own directory, so the
 * unzipped folder runs on machines without any JDK installed.
 * Build (MinGW cross, see scripts/build_portable.sh):
 *   x86_64-w64-mingw32-gcc -O2 -municode -mwindows -static -o HowReadPro.exe \
 *       launcher/main.c [app_res.o] */
#include <windows.h>
#include <wchar.h>
#include <stdio.h>
#include <string.h>

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, PWSTR cmdLine, int nShow)
{
    (void)hInst; (void)hPrev; (void)nShow;

    wchar_t exe[1024];
    DWORD n = GetModuleFileNameW(NULL, exe, 1023);
    if (n == 0 || n >= 1023) return 1;
    wchar_t *slash = wcsrchr(exe, L'\\');
    if (!slash) return 1;
    *slash = L'\0';

    /* v0.28.0 portable data dir: keep config/data next to the program and let the
     * engine's user-font search (hr_user_fonts.c honours HR_FONTS_DIR) follow it.
     * data-dir.txt (one UTF-8 line) moves the data folder, same rules as AppPaths. */
    {
        wchar_t dataDir[1024];
        wchar_t cfg[1200];
        wchar_t fonts[1200];
        int haveOverride = 0;
        _snwprintf(cfg, 1199, L"%ls\\data-dir.txt", exe);
        FILE *df = _wfopen(cfg, L"rb");
        if (df) {
            char line[1024];
            if (fgets(line, sizeof(line), df)) {
                char *p2 = line;
                if ((unsigned char)p2[0] == 0xEF && (unsigned char)p2[1] == 0xBB && (unsigned char)p2[2] == 0xBF) p2 += 3;
                size_t len2 = strlen(p2);
                while (len2 && (p2[len2-1]=='\r' || p2[len2-1]=='\n' || p2[len2-1]==' ' || p2[len2-1]=='\t')) p2[--len2] = 0;
                if (len2) {
                    wchar_t w[1024];
                    if (MultiByteToWideChar(CP_UTF8, 0, p2, -1, w, 1024) > 0) {
                        _snwprintf(dataDir, 1023, L"%ls", w);
                        dataDir[1023] = 0;   /* 检视修复：截断 NUL 兜底 */
                        haveOverride = 1;
                        SetEnvironmentVariableW(L"HOWREAD_DATA", w);
                    }
                }
            }
            fclose(df);
        }
        if (!haveOverride) _snwprintf(dataDir, 1023, L"%ls\\data", exe);
        _snwprintf(fonts, 1199, L"%ls\\fonts", dataDir);
        dataDir[1023] = 0;   /* 检视修复：截断 NUL 兜底 */
        fonts[1199] = 0;
        SetEnvironmentVariableW(L"HR_FONTS_DIR", fonts);
    }

    wchar_t cmdl[4096];
    _snwprintf(cmdl, 4095,
        L"\"%ls\\runtime\\bin\\javaw.exe\" "
        L"-Djava.library.path=\"%ls\\engine\" "
        L"-cp \"%ls\\app\\*\" howread.app.MainKt %ls",
        exe, exe, exe, cmdLine ? cmdLine : L"");
    cmdl[4095] = 0;   /* 检视修复：_snwprintf 截断时不保证 NUL 终止 */

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(NULL, cmdl, NULL, NULL, FALSE, 0, NULL, exe, &si, &pi)) {
        MessageBoxW(NULL,
            L"启动失败：请确认 HowReadPro.exe 与 runtime\\、app\\、engine\\ 位于同一目录。",
            L"HowReadPro", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return 0;
}
