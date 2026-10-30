#ifndef _HOWREAD_JNI_MD_WIN32_H_
#define _HOWREAD_JNI_MD_WIN32_H_
/* HowRead 2026-10-25: win32/x64 jni_md.h equivalent for MinGW cross-compile.
   Content mirrors OpenJDK windows jni_md.h (x64: jint=int32, jlong=int64). */
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
