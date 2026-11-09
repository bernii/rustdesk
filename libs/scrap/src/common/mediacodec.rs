//! Android hardware H264/H265 decoding through the NDK MediaCodec API.
//!
//! On Android the `hwcodec` feature only provides FFmpeg *software* H26x decoders, so this
//! is the only hardware decode path. Output is read from ByteBuffers and converted to RGB
//! with libyuv; layout (stride, slice height, crop, NV12 vs I420) comes from the codec's
//! output format rather than being assumed.
use hbb_common::{bail, log, ResultType};
use ndk::media::{
    media_codec::{MediaCodec, MediaCodecDirection, MediaFormat},
    NdkMediaError,
};
use std::{
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

use crate::{CodecFormat, I420ToABGR, I420ToARGB, ImageFormat, ImageRgb, NV12ToABGR, NV12ToARGB};

crate::generate_call_macro!(call_yuv, false);

const H264_MIME_TYPE: &str = "video/avc";
const H265_MIME_TYPE: &str = "video/hevc";

// android.media.MediaCodecInfo.CodecCapabilities color formats
const COLOR_FORMAT_YUV420_PLANAR: i32 = 19;
const COLOR_FORMAT_YUV420_SEMIPLANAR: i32 = 21;
const COLOR_FORMAT_YUV420_FLEXIBLE: i32 = 0x7F42_0888;
// AMediaCodec_dequeueOutputBuffer informational results that ndk 0.7 reports as errors.
const INFO_OUTPUT_FORMAT_CHANGED: i32 = -2;
const INFO_OUTPUT_BUFFERS_CHANGED: i32 = -3;

const INPUT_TIMEOUT: Duration = Duration::from_millis(100);
// A hardware decoder normally returns a frame within a few milliseconds of its input.
// Waiting here (instead of returning early) avoids holding the newest frame until the
// next packet arrives, which may be seconds away on a static screen.
const OUTPUT_TIMEOUT: Duration = Duration::from_millis(100);
const CONVERT_THREADS: usize = 4;
const STATS_INTERVAL: Duration = Duration::from_secs(5);

pub static H264_DECODER_SUPPORT: AtomicBool = AtomicBool::new(false);

pub static H265_DECODER_SUPPORT: AtomicBool = AtomicBool::new(false);

#[derive(Debug, Clone, Copy, PartialEq)]
struct Layout {
    width: usize,
    height: usize,
    stride: usize,
    slice_height: usize,
    crop_left: usize,
    crop_top: usize,
    planar: bool,
}

#[derive(Default)]
struct Stats {
    since: Option<Instant>,
    frames: u32,
    decode_us: u128,
    decode_worst_us: u128,
    convert_us: u128,
}

pub struct MediaCodecDecoder {
    decoder: MediaCodec,
    name: String,
    layout: Option<Layout>,
    stats: Stats,
}

impl Drop for MediaCodecDecoder {
    fn drop(&mut self) {
        let _ = self.decoder.stop();
    }
}

impl MediaCodecDecoder {
    pub fn new(format: CodecFormat) -> Option<MediaCodecDecoder> {
        match format {
            CodecFormat::H264 => create_media_codec(H264_MIME_TYPE),
            CodecFormat::H265 => create_media_codec(H265_MIME_TYPE),
            _ => {
                log::error!("Unsupported codec format: {:?}", format);
                None
            }
        }
    }

    /// Decodes one access unit. Returns `Ok(true)` when `rgb` holds a new frame.
    pub fn decode(&mut self, data: &[u8], rgb: &mut ImageRgb) -> ResultType<bool> {
        let start = Instant::now();
        let Some(mut input) = self.decoder.dequeue_input_buffer(INPUT_TIMEOUT)? else {
            // Dropping compressed data silently would corrupt every following frame.
            bail!("MediaCodec {}: no input buffer within {INPUT_TIMEOUT:?}", self.name);
        };
        let buf = input.buffer_mut();
        if data.len() > buf.len() {
            bail!("MediaCodec {}: packet {} > input buffer {}", self.name, data.len(), buf.len());
        }
        buf[..data.len()].copy_from_slice(data);
        self.decoder.queue_input_buffer(input, 0, data.len(), 0, 0)?;

        // Wait for the first output, then drain anything else that is ready, keeping only
        // the newest picture.
        let mut got = false;
        let mut timeout = OUTPUT_TIMEOUT;
        let deadline = Instant::now() + OUTPUT_TIMEOUT;
        loop {
            match self.decoder.dequeue_output_buffer(timeout) {
                Ok(Some(output)) => {
                    let decoded = start.elapsed();
                    if self.layout.is_none() {
                        self.layout = Some(self.read_layout()?);
                    }
                    let convert_start = Instant::now();
                    let layout = self.layout.as_ref().unwrap();
                    let res = convert(output.buffer(), layout, rgb);
                    self.decoder.release_output_buffer(output, false)?;
                    res?;
                    self.record(decoded, convert_start.elapsed());
                    got = true;
                    timeout = Duration::ZERO;
                }
                Ok(None) => break,
                Err(NdkMediaError::UnknownResult(s)) if s.0 == INFO_OUTPUT_FORMAT_CHANGED => {
                    self.layout = Some(self.read_layout()?);
                    log::info!("MediaCodec {} output format: {:?}", self.name, self.layout);
                    timeout = if got { Duration::ZERO } else { deadline.saturating_duration_since(Instant::now()) };
                }
                Err(NdkMediaError::UnknownResult(s)) if s.0 == INFO_OUTPUT_BUFFERS_CHANGED => {}
                Err(e) => return Err(e.into()),
            }
        }
        if !got {
            log::debug!("MediaCodec {}: no output within {OUTPUT_TIMEOUT:?}", self.name);
        }
        Ok(got)
    }

    fn read_layout(&self) -> ResultType<Layout> {
        let f = self.decoder.output_format();
        let get = |key: &str| f.i32(key).filter(|v| *v >= 0).map(|v| v as usize);
        let (Some(w), Some(h)) = (get("width"), get("height")) else {
            bail!("MediaCodec {}: output format without size", self.name);
        };
        let (left, top) = (get("crop-left").unwrap_or(0), get("crop-top").unwrap_or(0));
        let width = get("crop-right").map_or(w, |r| r + 1 - left);
        let height = get("crop-bottom").map_or(h, |b| b + 1 - top);
        let color = f.i32("color-format").unwrap_or(COLOR_FORMAT_YUV420_SEMIPLANAR);
        let planar = match color {
            COLOR_FORMAT_YUV420_PLANAR => true,
            COLOR_FORMAT_YUV420_SEMIPLANAR | COLOR_FORMAT_YUV420_FLEXIBLE => false,
            other => bail!("MediaCodec {}: unsupported color format {other:#x}", self.name),
        };
        Ok(Layout {
            width,
            height,
            stride: get("stride").filter(|s| *s >= w).unwrap_or(w),
            slice_height: get("slice-height").filter(|s| *s >= h).unwrap_or(h),
            crop_left: left & !1,
            crop_top: top & !1,
            planar,
        })
    }

    fn record(&mut self, decoded: Duration, converted: Duration) {
        let s = &mut self.stats;
        let now = Instant::now();
        let since = *s.since.get_or_insert(now);
        s.frames += 1;
        s.decode_us += decoded.as_micros();
        s.decode_worst_us = s.decode_worst_us.max(decoded.as_micros());
        s.convert_us += converted.as_micros();
        if now.duration_since(since) >= STATS_INTERVAL {
            let n = s.frames as u128;
            log::info!(
                "MediaCodec {} {:?}: {} frames, input->output avg {:.1} worst {:.1} ms, to RGB avg {:.1} ms",
                self.name,
                self.layout.map(|l| (l.width, l.height, if l.planar { "I420" } else { "NV12" })),
                n,
                s.decode_us as f64 / n as f64 / 1e3,
                s.decode_worst_us as f64 / 1e3,
                s.convert_us as f64 / n as f64 / 1e3,
            );
            *s = Stats { since: Some(now), ..Default::default() };
        }
    }
}

fn convert(buf: &[u8], l: &Layout, rgb: &mut ImageRgb) -> ResultType<()> {
    let y_size = l.stride * l.slice_height;
    let chroma_rows = (l.slice_height + 1) / 2;
    let needed = if l.planar {
        y_size + 2 * (l.stride / 2) * chroma_rows
    } else {
        y_size + l.stride * chroma_rows
    };
    // The last plane may omit trailing padding; require only the visible rows.
    let visible_end = if l.planar {
        y_size + (l.stride / 2) * (chroma_rows + (l.crop_top + l.height + 1) / 2)
    } else {
        y_size + l.stride * ((l.crop_top + l.height + 1) / 2)
    };
    if buf.len() < visible_end.min(needed) {
        bail!("MediaCodec output {} bytes < expected {} for {:?}", buf.len(), visible_end, l);
    }
    rgb.w = l.width;
    rgb.h = l.height;
    let align = rgb.align().max(1);
    let bytes_per_row = (rgb.w * 4 + align - 1) & !(align - 1);
    rgb.raw.resize(rgb.h * bytes_per_row, 0);
    // Raw pointers aren't Send; pass addresses to the band threads.
    let base = buf.as_ptr() as usize;
    let dst = rgb.raw.as_mut_ptr() as usize;
    let y = base + l.crop_top * l.stride + l.crop_left;
    let fmt = rgb.fmt();
    // Convert in horizontal bands of an even number of rows, one per core. A 4K frame
    // takes about 16 ms on one core.
    let band = ((l.height + CONVERT_THREADS - 1) / CONVERT_THREADS + 1) & !1;
    let convert_band = |start: usize, rows: usize| -> ResultType<()> {
        let yp = (y + start * l.stride) as *const u8;
        let out = (dst + start * bytes_per_row) as *mut u8;
        if l.planar {
            let c_stride = l.stride / 2;
            let c_off = (l.crop_top / 2 + start / 2) * c_stride + l.crop_left / 2;
            let u = (base + y_size + c_off) as *const u8;
            let v = (base + y_size + c_stride * chroma_rows + c_off) as *const u8;
            let f = match fmt {
                ImageFormat::ARGB => I420ToARGB,
                ImageFormat::ABGR => I420ToABGR,
                fmt => bail!("unsupported rgb format {fmt:?}"),
            };
            call_yuv!(f(
                yp, l.stride as _, u, c_stride as _, v, c_stride as _,
                out, bytes_per_row as _, l.width as _, rows as _,
            ));
        } else {
            let uv = (base + y_size + (l.crop_top / 2 + start / 2) * l.stride + l.crop_left)
                as *const u8;
            let f = match fmt {
                ImageFormat::ARGB => NV12ToARGB,
                ImageFormat::ABGR => NV12ToABGR,
                fmt => bail!("unsupported rgb format {fmt:?}"),
            };
            call_yuv!(f(
                yp, l.stride as _, uv, l.stride as _,
                out, bytes_per_row as _, l.width as _, rows as _,
            ));
        }
        Ok(())
    };
    let starts: Vec<usize> = (0..l.height).step_by(band.max(2)).collect();
    std::thread::scope(|scope| {
        let handles: Vec<_> = starts[1..]
            .iter()
            .map(|&start| scope.spawn(move || convert_band(start, band.min(l.height - start))))
            .collect();
        convert_band(0, band.min(l.height))?;
        for handle in handles {
            handle
                .join()
                .map_err(|_| hbb_common::anyhow::anyhow!("conversion thread panicked"))??;
        }
        Ok(())
    })
}

fn create_media_codec(mime: &str) -> Option<MediaCodecDecoder> {
    let codec = MediaCodec::from_decoder_type(mime)?;
    // AMediaCodec_getName needs API 28; the library links against API 21.
    let name = mime.to_owned();
    let format = MediaFormat::new();
    format.set_str("mime", mime);
    // The real size comes from the bitstream; some Codec2 components reject 0×0.
    format.set_i32("width", 1920);
    format.set_i32("height", 1080);
    format.set_i32("color-format", COLOR_FORMAT_YUV420_FLEXIBLE);
    format.set_i32("low-latency", 1); // KEY_LOW_LATENCY, API 30; ignored when unsupported
    format.set_i32("priority", 0); // realtime
    if let Err(e) = codec.configure(&format, None, MediaCodecDirection::Decoder) {
        log::error!("MediaCodec {name}: configure failed: {e:?}");
        return None;
    }
    if let Err(e) = codec.start() {
        log::error!("MediaCodec {name}: start failed: {e:?}");
        return None;
    }
    log::info!("MediaCodec decoder started: {name} ({mime})");
    Some(MediaCodecDecoder {
        decoder: codec,
        name,
        layout: None,
        stats: Stats::default(),
    })
}

/// Probes hardware decoder availability once, off the caller's thread.
pub fn check_mediacodec() {
    std::thread::spawn(move || {
        // Dropping a decoder stops it.
        H264_DECODER_SUPPORT.store(MediaCodecDecoder::new(CodecFormat::H264).is_some(), Ordering::SeqCst);
        H265_DECODER_SUPPORT.store(MediaCodecDecoder::new(CodecFormat::H265).is_some(), Ordering::SeqCst);
        log::info!(
            "MediaCodec decoders: h264 {}, h265 {}",
            H264_DECODER_SUPPORT.load(Ordering::SeqCst),
            H265_DECODER_SUPPORT.load(Ordering::SeqCst)
        );
    });
}
