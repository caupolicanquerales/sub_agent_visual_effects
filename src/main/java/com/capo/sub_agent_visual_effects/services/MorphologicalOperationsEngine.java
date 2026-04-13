package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class MorphologicalOperationsEngine {

    // -----------------------------------------------------------------------
    // All OpenCV Morphological Operations.
    // Keys are lower-cased so applyOperation(name.toLowerCase()) always hits.
    //
    // Common params shared by most operations:
    //   ksize        (int,    3)       – structuring element side length
    //   shape        (String, "rect")  – rect | cross | ellipse
    //   iterations   (int,    1)       – number of times to apply
    //   anchorX/Y    (int,   -1)       – anchor point; -1,-1 = kernel centre
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry = Map.ofEntries(

        // 1. Erosion – shrinks bright regions; removes small white noise
        Map.entry("erode", (src, params) -> {
            Mat kernel = buildKernel(params);
            Mat dst    = new Mat();
            int iter   = ((Number) params.getOrDefault("iterations", 1)).intValue();
            Imgproc.erode(src, dst, kernel, anchor(params), iter);
            return dst;
        }),

        // 2. Dilation – expands bright regions; fills small holes
        Map.entry("dilate", (src, params) -> {
            Mat kernel = buildKernel(params);
            Mat dst    = new Mat();
            int iter   = ((Number) params.getOrDefault("iterations", 1)).intValue();
            Imgproc.dilate(src, dst, kernel, anchor(params), iter);
            return dst;
        }),

        // 3. Opening (erode → dilate) – removes small objects / thin protrusions
        Map.entry("opening", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.morphologyEx(src, dst, Imgproc.MORPH_OPEN,
                    buildKernel(params), anchor(params),
                    ((Number) params.getOrDefault("iterations", 1)).intValue());
            return dst;
        }),

        // 4. Closing (dilate → erode) – fills small holes / thin gaps
        Map.entry("closing", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.morphologyEx(src, dst, Imgproc.MORPH_CLOSE,
                    buildKernel(params), anchor(params),
                    ((Number) params.getOrDefault("iterations", 1)).intValue());
            return dst;
        }),

        // 5. Morphological gradient (dilate − erode) – highlights object edges
        Map.entry("gradient", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.morphologyEx(src, dst, Imgproc.MORPH_GRADIENT,
                    buildKernel(params), anchor(params),
                    ((Number) params.getOrDefault("iterations", 1)).intValue());
            return dst;
        }),

        // 6. Top-hat (src − opening) – extracts bright features smaller than the kernel
        //    Useful for highlighting fine bright details or uneven background correction
        Map.entry("tophat", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.morphologyEx(src, dst, Imgproc.MORPH_TOPHAT,
                    buildKernel(params), anchor(params),
                    ((Number) params.getOrDefault("iterations", 1)).intValue());
            return dst;
        }),

        // 7. Black-hat (closing − src) – extracts dark features smaller than the kernel
        //    Useful for highlighting fine dark details or dark spots on bright backgrounds
        Map.entry("blackhat", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.morphologyEx(src, dst, Imgproc.MORPH_BLACKHAT,
                    buildKernel(params), anchor(params),
                    ((Number) params.getOrDefault("iterations", 1)).intValue());
            return dst;
        }),

        // 8. Hit-or-Miss transform – detects specific binary pixel patterns
        //    Requires a binary (single-channel) input image.
        //    params: foreground and background patterns are supplied via the standard
        //            kernel, but for pattern-based detection a cross kernel (shape=cross)
        //            is typical. For pixel-exact control pass warpaffine with a custom
        //            2-channel structured element if needed.
        Map.entry("hitmiss", (src, params) -> {
            Mat dst = new Mat();
            Imgproc.morphologyEx(src, dst, Imgproc.MORPH_HITMISS,
                    buildKernel(params), anchor(params),
                    ((Number) params.getOrDefault("iterations", 1)).intValue());
            return dst;
        })
    );

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (src, p) -> src)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a structuring element from params: ksize (int,3), shape (String,"rect"). */
    private static Mat buildKernel(Map<String, Object> params) {
        int    k     = ((Number) params.getOrDefault("ksize", 3)).intValue();
        String shape = String.valueOf(params.getOrDefault("shape", "rect"));
        int morphShape = switch (shape.toLowerCase()) {
            case "cross"   -> Imgproc.MORPH_CROSS;
            case "ellipse" -> Imgproc.MORPH_ELLIPSE;
            default        -> Imgproc.MORPH_RECT;
        };
        return Imgproc.getStructuringElement(morphShape, new Size(k, k));
    }

    /** Reads anchorX/anchorY from params; defaults to (-1,-1) = kernel centre. */
    private static Point anchor(Map<String, Object> params) {
        int ax = ((Number) params.getOrDefault("anchorX", -1)).intValue();
        int ay = ((Number) params.getOrDefault("anchorY", -1)).intValue();
        return new Point(ax, ay);
    }
}
