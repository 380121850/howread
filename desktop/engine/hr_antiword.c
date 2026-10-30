/* HowRead desktop 2026-10-29 (v0.46.0): DOC(Word 97-2003) → DocBook XML 桥（libantiword-1.3.1）。
 * 复刻安卓 Builder/jni/libantiword-1.3.1/main_u.c 的 convertDocToHtml（conversion_xml + UTF-8 同配置）。
 * 差异：输出经 pDiag->pOutFile 直写目标文件——pCreateDiagram 默认把它指向 stdout，
 * 桌面 GUI 进程不重定向进程 stdout（vDestroyDiagram 只 xfree 不 fclose，由本函数收尾）。 */
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include "antiword.h"

#ifdef _WIN32
#include <windows.h>
/* 检视修复：JNI GetStringUTFChars 产出 UTF-8 字节，而 Windows 窄字符 fopen/lGetFilesize
 * 按 ANSI 代码页（中文系统=GBK）解释——路径含中文的 DOC 必然打不开。改走宽字符 _wfopen，
 * 文件大小自行 ftell（绕开 antiword 的窄字符 lGetFilesize）。 */
static FILE *hr_wfopen_utf8(const char *utf8, const wchar_t *mode)
{
    wchar_t w[4096];
    if (MultiByteToWideChar(CP_UTF8, 0, utf8, -1, w, 4096) <= 0)
        return NULL;
    return _wfopen(w, mode);
}
#endif

JNIEXPORT jint JNICALL
Java_howread_core_convert_HrDocNative_nativeDocToHtml(JNIEnv *env, jclass clazz, jstring jinput, jstring joutput)
{
    const char *in = NULL;
    const char *out = NULL;
    FILE *fOut = NULL;
    FILE *pFile = NULL;
    diagram_type *pDiag = NULL;
    long lFilesize;
    int iWordVersion;
    jint rc = 0;
    /* 选项与安卓 main_u.c setOptions 完全一致（位置初始化） */
    options_type tOptions = {
        0, conversion_xml, TRUE, TRUE, FALSE, encoding_utf_8, LONG_MAX, LONG_MAX, level_default,
    };
    (void)clazz;

    if (jinput == NULL || joutput == NULL)
        return 0;
    in = (*env)->GetStringUTFChars(env, jinput, NULL);
    out = (*env)->GetStringUTFChars(env, joutput, NULL);
    if (in == NULL || out == NULL)
        goto done;
    vSetOptions(&tOptions);

#ifdef _WIN32
    fOut = hr_wfopen_utf8(out, L"wb");
#else
    fOut = fopen(out, "wb");
#endif
    if (fOut == NULL)
        goto done;
#ifdef _WIN32
    pFile = hr_wfopen_utf8(in, L"rb");
    if (pFile != NULL) {
        if (fseek(pFile, 0, SEEK_END) != 0) lFilesize = -1;
        else lFilesize = ftell(pFile);
        if (fseek(pFile, 0, SEEK_SET) != 0) lFilesize = -1;
        if (lFilesize < 0) { fclose(pFile); pFile = NULL; }
    }
#else
    pFile = fopen(in, "rb");
    if (pFile != NULL) lFilesize = lGetFilesize(in);
#endif
    if (pFile == NULL || lFilesize < 0)
        goto done;
    iWordVersion = iGuessVersionNumber(pFile, lFilesize);
    if (iWordVersion < 0 || iWordVersion == 3)
        goto done;   /* 非 Word 6/95/97 文档 */
    rewind(pFile);

    pDiag = pCreateDiagram("HowRead", in);
    if (pDiag == NULL)
        goto done;
    pDiag->pOutFile = fOut;   /* 关键差异：正文直写目标文件，不碰 stdout */
    rc = bWordDecryptor(pFile, lFilesize, pDiag) ? 1 : 0;
    fflush(fOut);
    vDestroyDiagram(pDiag);   /* 仅 xfree；文件由本函数关闭 */
    pDiag = NULL;

done:
    if (pFile != NULL) fclose(pFile);
    if (fOut != NULL) fclose(fOut);
    if (in != NULL) (*env)->ReleaseStringUTFChars(env, jinput, in);
    if (out != NULL) (*env)->ReleaseStringUTFChars(env, joutput, out);
    return rc;
}
