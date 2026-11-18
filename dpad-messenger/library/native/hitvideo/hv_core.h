#ifndef HV_CORE_H
#define HV_CORE_H

#include <stdint.h>

typedef struct HV HV;

enum {
    HV_INFO_WIDTH = 0,
    HV_INFO_HEIGHT,
    HV_INFO_BIT_DEPTH,
    HV_INFO_FPS_NUM,
    HV_INFO_FPS_DEN,
    HV_INFO_DURATION_MS,
    HV_INFO_ROTATION,     /* clockwise degrees to show it upright: 0/90/180/270 */
    HV_INFO_FRAMES,       /* from the container, 0 if unknown */
    HV_INFO_CODEC_ID,     /* AVCodecID */
    HV_INFO_COLOR_TRC,    /* AVColorTransferCharacteristic (18 = HLG, 16 = PQ) */
    HV_INFO_LEN
};

HV *hv_open(const char *path, int threads, int fast, char *err, int errlen);
void hv_info(HV *hv, int out[HV_INFO_LEN]);
/* Output size for hv_next: even width/height; frames come out as tightly packed I420. */
int hv_set_output(HV *hv, int w, int h);
int hv_frame_size(HV *hv);
/* 1 = frame written to dst (pts in microseconds from the first frame), 0 = end, < 0 = error. */
int hv_next(HV *hv, uint8_t *dst, int dst_size, int64_t *pts_us);
void hv_close(HV *hv);

#endif
