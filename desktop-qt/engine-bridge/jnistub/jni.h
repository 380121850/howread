/* HowRead Qt 分支 v0.59.0：最小 JNI 头（仅服务 libantiword main_u.c 的死代码入口）。
 * Qt 构建无 JDK；该入口不被 Qt 调用、符号也不导出（导出面由 mupdfqt.def 收口），
 * 只需可编译的最小声明。 */
#ifndef HR_JNI_STUB_H
#define HR_JNI_STUB_H

#include <stdint.h>

typedef int32_t jint;
typedef uint8_t jboolean;
typedef int64_t jlong;
typedef struct _jstring *jstring;
typedef struct _jclass *jclass;

struct JNINativeInterface_
{
	const char *(*GetStringUTFChars)(void *, jstring, jboolean *);
	void (*ReleaseStringUTFChars)(void *, jstring, const char *);
};
typedef const struct JNINativeInterface_ *JNIEnv;

#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0

#endif /* HR_JNI_STUB_H */
