// emon_vision.m -- Apple Vision's text recogniser behind plain C (K8, D901).  See emon_vision.h.  Built by tools/native/vision.clj (clang, -fobjc-arc) into a static library
// that the Rust adapter links with the Vision, Foundation, CoreGraphics and ImageIO frameworks.  It reads one bitmap and returns rows; it decides nothing.
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <Vision/Vision.h>
#include <TargetConditionals.h>
#include <stdlib.h>
#include <string.h>
#include "emon_vision.h"

static char *dupe(NSString *s) {
    const char *u = s ? [s UTF8String] : "";
    return strdup(u ? u : "");
}

static void put(char *out, size_t cap, NSString *s) {
    if (!out || cap == 0) return;
    const char *u = [s UTF8String];
    if (!u) u = "";
    size_t n = strlen(u);
    if (n >= cap) n = cap - 1;
    memcpy(out, u, n);
    out[n] = 0;
}

void emon_vision_identity(char *out, size_t cap) {
    @autoreleasepool {
#if TARGET_OS_OSX
        NSString *os = @"macOS";
#elif TARGET_OS_SIMULATOR
        NSString *os = @"iOS-simulator";
#else
        NSString *os = @"iOS";
#endif
#if defined(__arm64__)
        NSString *arch = @"arm64";
#elif defined(__x86_64__)
        NSString *arch = @"x86_64";
#else
        NSString *arch = @"unknown";
#endif
        NSOperatingSystemVersion v = [NSProcessInfo processInfo].operatingSystemVersion;
        NSString *full = [NSProcessInfo processInfo].operatingSystemVersionString ?: @"";
        // "Version 14.5 (Build 23F79)" -> "23F79"
        NSString *build = @"";
        NSRange b = [full rangeOfString:@"(Build "];
        if (b.location != NSNotFound) {
            NSString *rest = [full substringFromIndex:b.location + b.length];
            NSRange e = [rest rangeOfString:@")"];
            build = e.location == NSNotFound ? rest : [rest substringToIndex:e.location];
        }
        NSUInteger rev = [VNRecognizeTextRequest currentRevision];
        put(out, cap, [NSString stringWithFormat:@"%@ %ld.%ld.%ld (%@) %@; Vision VNRecognizeTextRequest revision=%lu", os, (long)v.majorVersion, (long)v.minorVersion, (long)v.patchVersion, build, arch, (unsigned long)rev]);
    }
}

int emon_vision_languages(char *out, size_t cap) {
    @autoreleasepool {
        VNRecognizeTextRequest *r = [[VNRecognizeTextRequest alloc] init];
        r.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
        NSArray<NSString *> *l = nil;
        NSError *err = nil;
        if (@available(macOS 12.0, iOS 15.0, *)) {
            l = [r supportedRecognitionLanguagesAndReturnError:&err];
        }
        if (!l) { put(out, cap, @"en-US"); return -1; }
        put(out, cap, [l componentsJoinedByString:@","]);
        return 0;
    }
}

static void quad_of(VNRectangleObservation *o, double w, double h, ev_quad *q) {
    CGPoint p[4] = {o.topLeft, o.topRight, o.bottomRight, o.bottomLeft};
    for (int i = 0; i < 4; i++) { q->x[i] = p[i].x * w; q->y[i] = (1.0 - p[i].y) * h; }
}

// the whitespace-separated tokens of a string, as UTF-16 ranges
static NSArray<NSValue *> *tokens_of(NSString *s) {
    NSMutableArray *out = [NSMutableArray array];
    NSCharacterSet *ws = [NSCharacterSet whitespaceAndNewlineCharacterSet];
    NSUInteger n = s.length, i = 0;
    while (i < n) {
        while (i < n && [ws characterIsMember:[s characterAtIndex:i]]) i++;
        NSUInteger j = i;
        while (j < n && ![ws characterIsMember:[s characterAtIndex:j]]) j++;
        if (j > i) [out addObject:[NSValue valueWithRange:NSMakeRange(i, j - i)]];
        i = j;
    }
    return out;
}

int emon_vision_read(const uint8_t *pixels, int32_t width, int32_t height, int32_t channels, int32_t stride,
                     const char *const *langs, int32_t nlangs, int32_t fast, int32_t correct, int32_t ncands,
                     ev_result *out, char *err, size_t errcap) {
    @autoreleasepool {
        out->nlines = 0;
        out->lines = NULL;
        if (!pixels || width <= 0 || height <= 0 || (channels != 1 && channels != 3) || stride < width * channels) { put(err, errcap, @"bad bitmap"); return 1; }
        CGColorSpaceRef cs = channels == 1 ? CGColorSpaceCreateDeviceGray() : CGColorSpaceCreateDeviceRGB();
        CGDataProviderRef dp = CGDataProviderCreateWithData(NULL, pixels, (size_t)stride * (size_t)height, NULL);
        CGImageRef img = CGImageCreate((size_t)width, (size_t)height, 8, (size_t)channels * 8, (size_t)stride, cs, (CGBitmapInfo)kCGImageAlphaNone, dp, NULL, false, kCGRenderingIntentDefault);
        CGDataProviderRelease(dp);
        CGColorSpaceRelease(cs);
        if (!img) { put(err, errcap, @"could not wrap the bitmap as an image"); return 1; }

        VNRecognizeTextRequest *req = [[VNRecognizeTextRequest alloc] init];
        req.recognitionLevel = fast ? VNRequestTextRecognitionLevelFast : VNRequestTextRecognitionLevelAccurate;
        req.usesLanguageCorrection = correct ? YES : NO;
        if (nlangs > 0) {
            NSMutableArray *a = [NSMutableArray array];
            for (int i = 0; i < nlangs; i++) [a addObject:[NSString stringWithUTF8String:langs[i]]];
            req.recognitionLanguages = a;
        }
        if (ncands < 0) ncands = 0;
        if (ncands > 9) ncands = 9;
        VNImageRequestHandler *h = [[VNImageRequestHandler alloc] initWithCGImage:img options:@{}];
        NSError *e = nil;
        BOOL ok = [h performRequests:@[req] error:&e];
        CGImageRelease(img);
        if (!ok) { put(err, errcap, e ? e.localizedDescription : @"Vision failed"); return 2; }
        NSArray<VNRecognizedTextObservation *> *obs = req.results ?: @[];
        double W = width, H = height;
        ev_line *lines = (ev_line *)calloc(obs.count ? obs.count : 1, sizeof(ev_line));
        if (!lines) { put(err, errcap, @"out of memory"); return 3; }
        int32_t nl = 0;
        for (VNRecognizedTextObservation *o in obs) {
            NSArray<VNRecognizedText *> *top = [o topCandidates:(NSUInteger)(1 + ncands)];
            if (top.count == 0) continue;
            VNRecognizedText *t = top[0];
            if (t.string.length == 0) continue;
            ev_line *L = &lines[nl];
            L->text = dupe(t.string);
            L->confidence = t.confidence;
            quad_of(o, W, H, &L->quad);
            NSArray<NSValue *> *toks = tokens_of(t.string);
            L->nwords = (int32_t)toks.count;
            L->words = (ev_word *)calloc(toks.count ? toks.count : 1, sizeof(ev_word));
            double lx0 = L->quad.x[0], ly0 = L->quad.y[0], lx1 = L->quad.x[1], ly1 = L->quad.y[1];
            double bx0 = L->quad.x[3], by0 = L->quad.y[3], bx1 = L->quad.x[2], by1 = L->quad.y[2];
            double total = (double)t.string.length;
            for (NSUInteger k = 0; k < toks.count; k++) {
                NSRange r = toks[k].rangeValue;
                ev_word *w = &L->words[k];
                w->text = dupe([t.string substringWithRange:r]);
                NSError *e2 = nil;
                VNRectangleObservation *bb = [t boundingBoxForRange:r error:&e2];
                if (bb) {
                    quad_of(bb, W, H, &w->quad);
                    w->precise = 1;
                } else {
                    // the line's quad cut by character position
                    double f0 = (double)r.location / total, f1 = (double)(r.location + r.length) / total;
                    w->quad.x[0] = lx0 + (lx1 - lx0) * f0; w->quad.y[0] = ly0 + (ly1 - ly0) * f0;
                    w->quad.x[1] = lx0 + (lx1 - lx0) * f1; w->quad.y[1] = ly0 + (ly1 - ly0) * f1;
                    w->quad.x[2] = bx0 + (bx1 - bx0) * f1; w->quad.y[2] = by0 + (by1 - by0) * f1;
                    w->quad.x[3] = bx0 + (bx1 - bx0) * f0; w->quad.y[3] = by0 + (by1 - by0) * f0;
                    w->precise = 0;
                }
            }
            L->ncands = (int32_t)top.count - 1;
            if (L->ncands > 0) {
                L->cands = (ev_cand *)calloc((size_t)L->ncands, sizeof(ev_cand));
                for (int32_t c = 0; c < L->ncands; c++) {
                    L->cands[c].text = dupe(top[(NSUInteger)c + 1].string);
                    L->cands[c].confidence = top[(NSUInteger)c + 1].confidence;
                }
            }
            nl++;
        }
        out->nlines = nl;
        out->lines = lines;
        return 0;
    }
}

void emon_vision_free(ev_result *r) {
    if (!r || !r->lines) return;
    for (int32_t i = 0; i < r->nlines; i++) {
        ev_line *L = &r->lines[i];
        free(L->text);
        for (int32_t k = 0; k < L->nwords; k++) free(L->words[k].text);
        free(L->words);
        for (int32_t c = 0; c < L->ncands; c++) free(L->cands[c].text);
        free(L->cands);
    }
    free(r->lines);
    r->lines = NULL;
    r->nlines = 0;
}
