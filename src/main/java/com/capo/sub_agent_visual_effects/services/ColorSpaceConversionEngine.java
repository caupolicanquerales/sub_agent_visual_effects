package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class ColorSpaceConversionEngine {

    // -----------------------------------------------------------------------
    // All OpenCV Color Space Conversion operations.
    // Keys are lower-cased so applyConversion(name.toLowerCase()) always hits.
    //
    // Design note: every entry auto-detects the input channel count and picks
    // the appropriate cvtColor code so callers never need to worry about whether
    // the source is BGR, BGRA, or grayscale.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> conversionRegistry = Map.ofEntries(

        // 1. → Grayscale
        //    Single-channel luminance image.
        //    params: none
        Map.entry("togray", (src, params) -> {
            if (src.channels() == 1) return src;
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst,
                    src.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
            return dst;
        }),

        // 2. Gray → BGR
        //    Promotes a single-channel image to 3-channel by replicating the channel.
        //    params: none
        Map.entry("graytobgr", (src, params) -> {
            if (src.channels() >= 3) return src;
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_GRAY2BGR);
            return dst;
        }),

        // 3. BGR ↔ RGB (channel order swap)
        //    Swaps R and B channels; result is either RGB or BGR depending on source.
        //    params: none
        Map.entry("swaprbchannels", (src, params) -> {
            Mat dst = new Mat();
            int code = src.channels() == 4 ? Imgproc.COLOR_BGRA2RGBA : Imgproc.COLOR_BGR2RGB;
            Imgproc.cvtColor(src, dst, code);
            return dst;
        }),

        // 4. BGR → BGRA  (add alpha channel set to 255 = fully opaque)
        //    params: none
        Map.entry("tobgra", (src, params) -> {
            if (src.channels() == 4) return src;
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst,
                    src.channels() == 1 ? Imgproc.COLOR_GRAY2BGRA : Imgproc.COLOR_BGR2BGRA);
            return dst;
        }),

        // 5. BGRA → BGR  (remove alpha channel)
        //    params: none
        Map.entry("tobgr", (src, params) -> {
            if (src.channels() == 3) return src;
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst,
                    src.channels() == 1 ? Imgproc.COLOR_GRAY2BGR : Imgproc.COLOR_BGRA2BGR);
            return dst;
        }),

        // 6. BGR → HSV
        //    8-bit: H in [0,179], S/V in [0,255].
        //    params: none
        Map.entry("tohsv", (src, params) -> {
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2HSV);
            return dst;
        }),

        // 7. HSV → BGR
        //    params: none
        Map.entry("fromhsv", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_HSV2BGR);
            return dst;
        }),

        // 8. BGR → HLS  (Hue, Lightness, Saturation)
        //    8-bit: H in [0,179], L/S in [0,255].
        //    params: none
        Map.entry("tohls", (src, params) -> {
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2HLS);
            return dst;
        }),

        // 9. HLS → BGR
        //    params: none
        Map.entry("fromhls", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_HLS2BGR);
            return dst;
        }),

        // 10. BGR → CIE L*a*b*
        //     For 8-bit input: L in [0,255], a/b in [0,255] (shifted from [-128,127]).
        //     params: normalize (boolean, false) – normalise each channel to [0,255]
        //             for display when the output will be viewed directly.
        Map.entry("tolab", (src, params) -> {
            boolean normalize = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("normalize", false)));
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2Lab);
            if (normalize) dst = normalizeChannels(dst);
            return dst;
        }),

        // 11. CIE L*a*b* → BGR
        //     params: none
        Map.entry("fromlab", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_Lab2BGR);
            return dst;
        }),

        // 12. BGR → CIE L*u*v*
        //     params: normalize (boolean, false)
        Map.entry("toluv", (src, params) -> {
            boolean normalize = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("normalize", false)));
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2Luv);
            if (normalize) dst = normalizeChannels(dst);
            return dst;
        }),

        // 13. CIE L*u*v* → BGR
        //     params: none
        Map.entry("fromluv", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_Luv2BGR);
            return dst;
        }),

        // 14. BGR → YCrCb  (luma + chroma – used in JPEG compression)
        //     params: none
        Map.entry("toycrcb", (src, params) -> {
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2YCrCb);
            return dst;
        }),

        // 15. YCrCb → BGR
        //     params: none
        Map.entry("fromycrcb", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_YCrCb2BGR);
            return dst;
        }),

        // 16. BGR → YUV  (luma + chrominance – PAL/NTSC broadcast standard)
        //     params: none
        Map.entry("toyuv", (src, params) -> {
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2YUV);
            return dst;
        }),

        // 17. YUV → BGR
        //     params: none
        Map.entry("fromyuv", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_YUV2BGR);
            return dst;
        }),

        // 18. BGR → CIE XYZ  (device-independent tristimulus colour space)
        //     params: normalize (boolean, false)
        Map.entry("toxyz", (src, params) -> {
            boolean normalize = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("normalize", false)));
            Mat bgr = ensureBgr(src);
            Mat dst = new Mat();
            Imgproc.cvtColor(bgr, dst, Imgproc.COLOR_BGR2XYZ);
            if (normalize) dst = normalizeChannels(dst);
            return dst;
        }),

        // 19. CIE XYZ → BGR
        //     params: none
        Map.entry("fromxyz", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_XYZ2BGR);
            return dst;
        }),

        // 20. Extract a single channel by index
        //     Returns a single-channel 8-bit Mat of the requested channel.
        //     params: channel (int, 0) – channel index to keep (0=first, 1=second, 2=third)
        Map.entry("extractchannel", (src, params) -> {
            int idx = ((Number) params.getOrDefault("channel", 0)).intValue();
            idx = Math.min(idx, src.channels() - 1);
            Mat dst = new Mat();
            Core.extractChannel(src, dst, idx);
            return dst;
        }),

        // 21. Merge separate channels back into a multi-channel Mat
        //     This entry merges the source image with itself as a demonstrative no-op;
        //     the real use-case is to call this after per-channel manipulation.
        //     params: none – preserves all channels of the source image as-is
        //     (Included so the agent can reference the operation for pipeline building)
        Map.entry("mergechannels", (src, params) -> src)
    );

    public Mat applyConversion(String conversionName, Mat input, Map<String, Object> params) {
        return conversionRegistry.getOrDefault(conversionName.toLowerCase(), (src, p) -> src)
                                 .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Ensures a 3-channel BGR Mat regardless of whether src is gray or BGRA. */
    private static Mat ensureBgr(Mat src) {
        if (src.channels() == 3) return src;
        Mat dst = new Mat();
        if (src.channels() == 1) {
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_GRAY2BGR);
        } else if (src.channels() == 4) {
            Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2BGR);
        } else {
            return src;
        }
        return dst;
    }

    /**
     * Normalises each channel independently to [0, 255] (CV_8U).
     * Useful for visualising colour spaces like Lab, Luv, or XYZ whose channels
     * may not directly map to the [0, 255] display range.
     */
    private static Mat normalizeChannels(Mat src) {
        Mat dst = new Mat(src.size(), CvType.CV_8UC(src.channels()));
        Mat srcConverted = new Mat();
        src.convertTo(srcConverted, CvType.CV_32F);
        Core.normalize(srcConverted, dst, 0, 255, Core.NORM_MINMAX, CvType.CV_8U);
        return dst;
    }
}
