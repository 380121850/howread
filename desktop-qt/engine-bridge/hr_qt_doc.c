/* HowRead Qt 分支 v0.59.0：DOC(Word 6/95/97) → DocBook XML 桥（libantiword-1.3.1）。
 * 桌面 hr_antiword.c 同款复刻（去 JNI）：conversion_xml + UTF-8 同配置；
 * 输出经 pDiag->pOutFile 直写目标文件——pCreateDiagram 默认把它指向 stdout，
 * GUI 进程不重定向进程 stdout（vDestroyDiagram 只 xfree 不 fclose，由本函数收尾）。
 * Windows 窄字符 fopen 按 ANSI 代码页解释——中文路径走 _wfopen（入参 UTF-8）。 */
#include <stdio.h>
#include <string.h>
#include "antiword.h"

#ifdef _WIN32
#include <windows.h>
static FILE *hr_wfopen_utf8(const char *utf8, const wchar_t *mode)
{
	wchar_t w[4096];
	if (MultiByteToWideChar(CP_UTF8, 0, utf8, -1, w, 4096) <= 0)
		return NULL;
	return _wfopen(w, mode);
}
#endif

int hr_qt_doc_to_html(const char *in, const char *out)
{
	FILE *fOut = NULL;
	FILE *pFile = NULL;
	diagram_type *pDiag = NULL;
	long lFilesize = -1;
	int iWordVersion;
	int rc = 0;
	/* 选项与安卓 main_u.c setOptions 完全一致（位置初始化） */
	options_type tOptions = {
		0, conversion_xml, TRUE, TRUE, FALSE, encoding_utf_8, LONG_MAX, LONG_MAX, level_default,
	};

	if (in == NULL || out == NULL || !in[0] || !out[0])
		return 0;
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
	if (pFile != NULL)
	{
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
		goto done; /* 非 Word 6/95/97 文档 */
	rewind(pFile);

	pDiag = pCreateDiagram("HowRead", in);
	if (pDiag == NULL)
		goto done;
	pDiag->pOutFile = fOut; /* 关键：正文直写目标文件，不碰 stdout */
	rc = bWordDecryptor(pFile, lFilesize, pDiag) ? 1 : 0;
	fflush(fOut);
	vDestroyDiagram(pDiag); /* 仅 xfree；文件由本函数关闭 */
	pDiag = NULL;

done:
	if (pFile != NULL) fclose(pFile);
	if (fOut != NULL) fclose(fOut);
	return rc;
}
