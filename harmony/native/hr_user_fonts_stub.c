/* HarmonyOS 0.9.24: stub for the desktop external-user-font hook.
 * Builder/mupdf-1.23.7/source/html/html-font.c (2026-10-26 desktop round)
 * declares `extern fz_font *hr_load_user_font(...)` without any feature
 * guard, and the HarmonyOS engine build compiles that file. HarmonyOS has
 * no user-font directory feature; returning NULL makes html-font.c fall
 * through to the built-in fonts - exactly the behaviour before the desktop
 * round. Without this definition the engine link fails on the symbol. */
#include "mupdf/fitz/font.h"

fz_font *hr_load_user_font(fz_context *ctx, const char *name, int bold,
                           int italic, int needs_exact_metrics)
{
    (void)ctx;
    (void)name;
    (void)bold;
    (void)italic;
    (void)needs_exact_metrics;
    return NULL;
}
