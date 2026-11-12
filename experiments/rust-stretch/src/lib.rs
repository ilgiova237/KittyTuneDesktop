use ssstretch::Stretch;
use std::panic::{catch_unwind, AssertUnwindSafe};

pub struct Engine {
    stretch: Stretch,
    channels: usize,
    maximum: usize,
    input: [Vec<f32>; 2],
    output: [Vec<f32>; 2],
    remainder: f64,
    pitch: f32,
}

fn guard(f: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(-2)
}

#[no_mangle]
pub extern "C" fn kt_stretch_abi() -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn kt_stretch_create(rate: i32, channels: i32, maximum: i32) -> *mut Engine {
    if !(8000..=192000).contains(&rate)
        || !(1..=2).contains(&channels)
        || !(64..=8192).contains(&maximum)
    {
        return std::ptr::null_mut();
    }
    catch_unwind(AssertUnwindSafe(|| {
        let mut stretch = Stretch::with_seed(0);
        stretch.preset_default(channels, rate as f32);
        Box::into_raw(Box::new(Engine {
            stretch,
            channels: channels as usize,
            maximum: maximum as usize,
            input: std::array::from_fn(|_| vec![0.0; maximum as usize]),
            output: std::array::from_fn(|_| vec![0.0; maximum as usize * 10 + 1]),
            remainder: 0.0,
            pitch: 1.0,
        }))
    }))
    .unwrap_or(std::ptr::null_mut())
}

// Handles and buffers must belong to the caller, remain alive, and stay on one thread.
#[no_mangle]
pub unsafe extern "C" fn kt_stretch_process(
    engine: *mut Engine,
    input: *const i16,
    frames: i32,
    output: *mut i16,
    capacity_frames: i32,
    speed: f32,
    pitch: f32,
) -> i32 {
    guard(|| {
        if engine.is_null()
            || input.is_null()
            || output.is_null()
            || frames < 4
            || !speed.is_finite()
            || !pitch.is_finite()
            || !(0.1..=4.0).contains(&speed)
            || !(0.1..=4.0).contains(&pitch)
        {
            return -1;
        }
        let e = &mut *engine;
        if frames as usize > e.maximum {
            return -1;
        }
        let wanted = frames as f64 / speed as f64 + e.remainder;
        let count = (wanted + 1e-9).floor() as usize;
        if capacity_frames < count as i32 || count > e.output[0].len() {
            return -1;
        }
        let src = std::slice::from_raw_parts(input, frames as usize * e.channels);
        for frame in 0..frames as usize {
            for ch in 0..e.channels {
                e.input[ch][frame] = src[frame * e.channels + ch] as f32 / 32768.0;
            }
        }
        if pitch != e.pitch {
            e.stretch.set_transpose_factor(pitch, None);
            e.pitch = pitch;
        }
        let inputs = [e.input[0].as_ptr(), e.input[1].as_ptr()];
        let mut outputs = [e.output[0].as_mut_ptr(), e.output[1].as_mut_ptr()];
        e.stretch.process(
            &inputs[..e.channels],
            frames,
            &mut outputs[..e.channels],
            count as i32,
        );
        e.remainder = (wanted - count as f64).max(0.0);
        write_output(e, output, count);
        count as i32
    })
}

unsafe fn write_output(e: &Engine, output: *mut i16, frames: usize) {
    let dst = std::slice::from_raw_parts_mut(output, frames * e.channels);
    for frame in 0..frames {
        for ch in 0..e.channels {
            dst[frame * e.channels + ch] = (e.output[ch][frame] * 32768.0)
                .round()
                .clamp(-32768.0, 32767.0) as i16;
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn kt_stretch_flush(
    engine: *mut Engine,
    output: *mut i16,
    frames: i32,
) -> i32 {
    guard(|| {
        if engine.is_null() || output.is_null() || frames <= 0 {
            return -1;
        }
        let e = &mut *engine;
        if frames as usize > e.output[0].len() {
            return -1;
        }
        let mut outputs = [e.output[0].as_mut_ptr(), e.output[1].as_mut_ptr()];
        e.stretch.flush(&mut outputs[..e.channels], frames);
        write_output(e, output, frames as usize);
        frames
    })
}

#[no_mangle]
pub unsafe extern "C" fn kt_stretch_latency(engine: *mut Engine, speed: f32) -> i32 {
    guard(|| {
        if engine.is_null() || !speed.is_finite() || !(0.1..=4.0).contains(&speed) {
            return -1;
        }
        let e = &*engine;
        (e.stretch.input_latency() as f64 / speed as f64).ceil() as i32 + e.stretch.output_latency()
    })
}

#[no_mangle]
pub unsafe extern "C" fn kt_stretch_reset(engine: *mut Engine) -> i32 {
    guard(|| {
        if engine.is_null() {
            return -1;
        }
        let e = &mut *engine;
        e.stretch.reset();
        e.remainder = 0.0;
        e.pitch = f32::NAN;
        0
    })
}

#[no_mangle]
pub unsafe extern "C" fn kt_stretch_destroy(engine: *mut Engine) {
    if !engine.is_null() {
        drop(Box::from_raw(engine));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_unsupported_formats() {
        assert!(kt_stretch_create(0, 2, 1024).is_null());
        assert!(kt_stretch_create(48000, 3, 1024).is_null());
    }
    #[test]
    fn invalid_capacity_does_not_consume_input() {
        unsafe {
            let e = kt_stretch_create(48000, 2, 1024);
            let src = vec![0; 2048];
            let mut dst = vec![123; 20480];
            assert_eq!(
                kt_stretch_process(e, src.as_ptr(), 1024, dst.as_mut_ptr(), 0, 0.75, 1.0),
                -1
            );
            assert!(dst.iter().all(|&v| v == 123));
            assert_eq!(
                kt_stretch_process(e, src.as_ptr(), 1024, dst.as_mut_ptr(), 10240, 0.75, 1.0),
                1365
            );
            kt_stretch_destroy(e);
        }
    }
    #[test]
    fn fractional_duration_is_carried_between_blocks() {
        unsafe {
            let e = kt_stretch_create(48000, 1, 1024);
            let src = vec![0; 1024];
            let mut dst = vec![0; 10240];
            let frames: i32 = (0..3)
                .map(|_| {
                    kt_stretch_process(e, src.as_ptr(), 1024, dst.as_mut_ptr(), 10240, 0.75, 1.0)
                })
                .sum();
            assert_eq!(frames, 4096);
            assert_eq!(kt_stretch_reset(e), 0);
            kt_stretch_destroy(e);
        }
    }
}
