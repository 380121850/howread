/* M0 探针：hr_qt_open → render → PNG（与 Win207FontCmp 同参：fit-width/RGB/NORMAL）。 */
#include <QGuiApplication>
#include <QImage>
#include <cstdio>
#include <cstdlib>
#include "hr_bridge.h"

#ifdef QT_STATIC
#include <QtPlugin>
Q_IMPORT_PLUGIN(QWindowsIntegrationPlugin)
#endif

int main(int argc, char **argv)
{
    if (argc < 4) { fprintf(stderr, "usage: qtprobe book outPrefix width [pages...]\n"); return 2; }
    QGuiApplication app(argc, argv);
    if (hr_qt_init() != 0) { fprintf(stderr, "init fail: %s\n", hr_qt_last_error()); return 2; }
    const char *book = argv[1], *prefix = argv[2];
    int width = atoi(argv[3]);
    void *doc = hr_qt_open(book);
    if (!doc) { fprintf(stderr, "open fail: %s (%s)\n", book, hr_qt_last_error()); return 3; }
    printf("pages=%d\n", hr_qt_count(doc));
    for (int i = 4; i < argc; i++)
    {
        int page = atoi(argv[i]);
        int w = 0, h = 0;
        unsigned char *rgb = NULL;
        if (hr_qt_render(doc, page, width, 0, 0, &w, &h, &rgb) != 0)
        {
            fprintf(stderr, "render fail p%d: %s\n", page + 1, hr_qt_last_error());
            continue;
        }
        QImage img(rgb, w, h, w * 3, QImage::Format_RGB888);
        QString out = QString("%1_qt_p%2.png").arg(prefix).arg(page + 1);
        img.save(out);
        free(rgb);
        printf("WROTE %s %dx%d\n", out.toUtf8().constData(), w, h);
    }
    hr_qt_close(doc);
    printf("QT_PROBE_DONE\n");
    return 0;
}
