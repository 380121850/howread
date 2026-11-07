/* HowRead Qt 分支最小 getopt 头：jnistub 经 BEFORE 置于 -I 首位，
 * 用标准三参原型盖过 jbig2dec 目录 K&R `extern int getopt();` 与
 * antiword options.c getopt(argc,argv,...) 的冲突。 */
#ifndef HR_GETOPT_H
#define HR_GETOPT_H

#ifdef __cplusplus
extern "C" {
#endif

extern int getopt(int argc, char *const argv[], const char *optstring);
extern char *optarg;
extern int optind, opterr, optopt;

#ifdef __cplusplus
}
#endif

#endif /* HR_GETOPT_H */
