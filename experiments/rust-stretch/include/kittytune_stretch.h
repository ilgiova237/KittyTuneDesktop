#pragma once
#include <stdint.h>

/* One owner thread per handle. PCM buffers cannot alias; counts are frames, not samples.
   A handle is valid until exactly one destroy. Reuse after destroy is undefined.
   Status: nonnegative frame count, -1 invalid arguments/capacity, -2 Rust panic.
   Process accepts 4..maximum input frames. Create/configure can allocate.
   No wrapper buffer growth occurs during process. */
int32_t kt_stretch_abi(void);
void *kt_stretch_create(int32_t sample_rate, int32_t channels, int32_t maximum_input_frames);
int32_t kt_stretch_process(void *, const int16_t *, int32_t input_frames, int16_t *, int32_t output_capacity_frames, float speed, float pitch);
int32_t kt_stretch_flush(void *, int16_t *, int32_t frames);
int32_t kt_stretch_latency(void *, float speed);
int32_t kt_stretch_reset(void *);
void kt_stretch_destroy(void *);
