package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class ThresholdingSegmentationEngine {

    // -----------------------------------------------------------------------
    // All OpenCV Thresholding & Segmentation operations.
    // Keys are lower-cased so applyOperation(name.toLowerCase()) always hits.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry = Map.ofEntries(

        // 1. Simple threshold – applies a fixed-level threshold to each pixel
        //    params: thresh (double, 127), maxval (double, 255),
        //            type (String, "binary") – binary|binary_inv|trunc|tozero|tozero_inv|otsu|triangle
        //    Note: auto-converts to grayscale if input is multi-channel.
        Map.entry("threshold", (src, params) -> {
            double thresh  = ((Number) params.getOrDefault("thresh", 127  )).doubleValue();
            double maxval  = ((Number) params.getOrDefault("maxval", 255  )).doubleValue();
            String typeStr = String.valueOf(params.getOrDefault("type", "binary"));
            Mat gray = toGray(src);
            Mat dst  = new Mat();
            Imgproc.threshold(gray, dst, thresh, maxval, thresholdType(typeStr));
            return dst;
        }),

        // 2. Adaptive threshold – threshold computed from local neighbourhood
        //    Requires single-channel input; auto-converts to grayscale.
        //    params: maxValue (double, 255),
        //            method (String, "gaussian") – gaussian|mean,
        //            type (String, "binary") – binary|binary_inv,
        //            blockSize (int, 11, must be odd ≥ 3),
        //            C (double, 2.0 – subtracted from mean/weighted mean)
        Map.entry("adaptivethreshold", (src, params) -> {
            double maxValue = ((Number) params.getOrDefault("maxValue", 255  )).doubleValue();
            String method   = String.valueOf(params.getOrDefault("method",    "gaussian"));
            String typeStr  = String.valueOf(params.getOrDefault("type",      "binary"));
            int    blockSize = ((Number) params.getOrDefault("blockSize", 11 )).intValue();
            double C        = ((Number) params.getOrDefault("C",           2.0)).doubleValue();
            int oddBlock    = blockSize < 3 ? 3 : (blockSize % 2 == 0 ? blockSize + 1 : blockSize);
            int adaptMethod = "mean".equalsIgnoreCase(method)
                    ? Imgproc.ADAPTIVE_THRESH_MEAN_C : Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C;
            int type        = "binary_inv".equalsIgnoreCase(typeStr)
                    ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY;
            Mat gray = toGray(src);
            Mat dst  = new Mat();
            Imgproc.adaptiveThreshold(gray, dst, maxValue, adaptMethod, type, oddBlock, C);
            return dst;
        }),

        // 3. In-range colour segmentation – returns a binary mask
        //    where pixels within [lower, upper] = 255, others = 0.
        //    params: colorSpace (String, "bgr") – bgr|hsv,
        //            lower0/1/2 (double, 0), upper0/1/2 (double, 255)
        //    Tip for HSV skin tone: colorSpace=hsv, lower0=0,lower1=20,lower2=70,
        //                           upper0=20,upper1=255,upper2=255
        Map.entry("inrange", (src, params) -> {
            String cs = String.valueOf(params.getOrDefault("colorSpace", "bgr"));
            double l0 = ((Number) params.getOrDefault("lower0", 0  )).doubleValue();
            double l1 = ((Number) params.getOrDefault("lower1", 0  )).doubleValue();
            double l2 = ((Number) params.getOrDefault("lower2", 0  )).doubleValue();
            double u0 = ((Number) params.getOrDefault("upper0", 255)).doubleValue();
            double u1 = ((Number) params.getOrDefault("upper1", 255)).doubleValue();
            double u2 = ((Number) params.getOrDefault("upper2", 255)).doubleValue();
            Mat input = "hsv".equalsIgnoreCase(cs) ? bgrToHsv(src) : src;
            Mat dst   = new Mat();
            Core.inRange(input, new Scalar(l0, l1, l2), new Scalar(u0, u1, u2), dst);
            return dst;
        }),

        // 4. Flood fill – colours a connected region starting from a seed point
        //    The source image is cloned; the original is not modified.
        //    params: seedX (int, cols/2), seedY (int, rows/2),
        //            fillR/G/B (double, 255/0/0 – red fill),
        //            loDiff (double, 20 – lower colour difference tolerance),
        //            upDiff (double, 20 – upper colour difference tolerance)
        Map.entry("floodfill", (src, params) -> {
            int    seedX  = ((Number) params.getOrDefault("seedX",  src.cols() / 2)).intValue();
            int    seedY  = ((Number) params.getOrDefault("seedY",  src.rows() / 2)).intValue();
            double fillR  = ((Number) params.getOrDefault("fillR",  255)).doubleValue();
            double fillG  = ((Number) params.getOrDefault("fillG",    0)).doubleValue();
            double fillB  = ((Number) params.getOrDefault("fillB",    0)).doubleValue();
            double loDiff = ((Number) params.getOrDefault("loDiff",  20)).doubleValue();
            double upDiff = ((Number) params.getOrDefault("upDiff",  20)).doubleValue();
            Mat dst  = src.clone();
            Mat mask = new Mat(src.rows() + 2, src.cols() + 2, CvType.CV_8UC1, Scalar.all(0));
            Imgproc.floodFill(dst, mask,
                    new Point(seedX, seedY),
                    new Scalar(fillB, fillG, fillR),
                    new Rect(),
                    new Scalar(loDiff, loDiff, loDiff),
                    new Scalar(upDiff, upDiff, upDiff),
                    Imgproc.FLOODFILL_FIXED_RANGE);
            return dst;
        }),

        // 5. K-means colour quantization – reduces the image to k distinct colours
        //    params: k (int, 4), attempts (int, 3),
        //            maxIter (int, 100), epsilon (double, 0.2)
        Map.entry("kmeans", (src, params) -> {
            int    k       = ((Number) params.getOrDefault("k",        4  )).intValue();
            int    attempt = ((Number) params.getOrDefault("attempts",  3  )).intValue();
            int    maxIter = ((Number) params.getOrDefault("maxIter",   100)).intValue();
            double epsilon = ((Number) params.getOrDefault("epsilon",   0.2)).doubleValue();
            int    total   = src.rows() * src.cols();
            int    ch      = src.channels();
            // Reshape: (H×W) rows, ch columns, single-channel float
            Mat srcFloat = new Mat();
            src.convertTo(srcFloat, CvType.CV_32F);
            Mat data    = srcFloat.reshape(1, total);
            Mat labels  = new Mat();
            Mat centers = new Mat();
            TermCriteria criteria = new TermCriteria(
                    TermCriteria.COUNT + TermCriteria.EPS, maxIter, epsilon);
            Core.kmeans(data, k, labels, criteria, attempt, Core.KMEANS_RANDOM_CENTERS, centers);
            // Reconstruct: replace each pixel with its cluster centre colour
            float[] quantizedData = new float[total * ch];
            for (int i = 0; i < total; i++) {
                int label = (int) labels.get(i, 0)[0];
                for (int c = 0; c < ch; c++) {
                    quantizedData[i * ch + c] = (float) centers.get(label, c)[0];
                }
            }
            Mat quantized = new Mat(total, ch, CvType.CV_32F);
            quantized.put(0, 0, quantizedData);
            Mat result = new Mat();
            quantized.reshape(ch, src.rows()).convertTo(result, src.type());
            return result;
        }),

        // 6. Connected components – labels each distinct region and colorizes the result
        //    Input is auto-binarized (Otsu); background label=0 is shown as black.
        //    params: connectivity (int, 8) – 4|8
        Map.entry("connectedcomponents", (src, params) -> {
            int  connectivity = ((Number) params.getOrDefault("connectivity", 8)).intValue();
            Mat  binary       = toBinary(src);
            Mat  labels       = new Mat();
            int  numLabels    = Imgproc.connectedComponents(binary, labels, connectivity, CvType.CV_32S);
            // Normalize label indices to 0-255, then apply a pseudo-colour map
            Mat labelU8 = new Mat();
            labels.convertTo(labelU8, CvType.CV_8U, 255.0 / Math.max(numLabels - 1, 1));
            Mat colored = new Mat();
            Imgproc.applyColorMap(labelU8, colored, Imgproc.COLORMAP_JET);
            // Force background (label == 0) to black
            Mat bgMask = new Mat();
            Core.compare(labels, new Scalar(0), bgMask, Core.CMP_EQ);
            colored.setTo(new Scalar(0, 0, 0), bgMask);
            return colored;
        }),

        // 7. Distance transform – computes distance from each binary foreground pixel
        //    to the nearest background pixel; output is heat-map colourised.
        //    Input is auto-binarized (Otsu).
        //    params: distType (String, "l2") – l1|l2|c,
        //            maskSize (int, 5) – 3|5|0 (0=precise for l2)
        Map.entry("distancetransform", (src, params) -> {
            String distTypeStr = String.valueOf(params.getOrDefault("distType", "l2"));
            int    maskSize    = ((Number) params.getOrDefault("maskSize", 5)).intValue();
            int distType = switch (distTypeStr.toLowerCase()) {
                case "l1" -> Imgproc.DIST_L1;
                case "c"  -> Imgproc.DIST_C;
                default   -> Imgproc.DIST_L2;
            };
            Mat binary = toBinary(src);
            Mat dist   = new Mat();
            Imgproc.distanceTransform(binary, dist, distType, maskSize);
            Mat normalized = new Mat();
            Core.normalize(dist, normalized, 0, 255, Core.NORM_MINMAX, CvType.CV_8U);
            Mat colored = new Mat();
            Imgproc.applyColorMap(normalized, colored, Imgproc.COLORMAP_HOT);
            return colored;
        }),

        // 8. Watershed segmentation – fully automatic marker generation
        //    Produces a BGR image with segment boundaries drawn in red.
        //    Marker generation pipeline: Otsu → dilation (sure bg) →
        //      distance transform + threshold (sure fg) → unknown region →
        //      connectedComponents → watershed.
        //    No params required.
        Map.entry("watershed", (src, params) -> {
            Mat bgr  = toColor(src);
            Mat gray = toGray(bgr);
            // Step 1: sure background via Otsu + dilation
            Mat thresh = new Mat();
            Imgproc.threshold(gray, thresh, 0, 255,
                    Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Mat sureBg = new Mat();
            Imgproc.dilate(thresh, sureBg, kernel, new Point(-1, -1), 3);
            // Step 2: sure foreground via distance transform
            Mat dist = new Mat();
            Imgproc.distanceTransform(thresh, dist, Imgproc.DIST_L2, 5);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(dist);
            Mat sureFg = new Mat();
            Imgproc.threshold(dist, sureFg, 0.7 * mmr.maxVal, 255, Imgproc.THRESH_BINARY);
            sureFg.convertTo(sureFg, CvType.CV_8U);
            // Step 3: unknown region
            Mat unknown = new Mat();
            Core.subtract(sureBg, sureFg, unknown);
            // Step 4: label connected components; reserve 0 for unknown
            Mat markers = new Mat();
            Imgproc.connectedComponents(sureFg, markers, 8, CvType.CV_32S);
            Core.add(markers, new Scalar(1), markers);
            markers.setTo(new Scalar(0), unknown);
            // Step 5: apply watershed and colour boundaries red
            Imgproc.watershed(bgr, markers);
            Mat result       = bgr.clone();
            Mat boundaryMask = new Mat();
            Core.compare(markers, new Scalar(-1), boundaryMask, Core.CMP_EQ);
            result.setTo(new Scalar(0, 0, 255), boundaryMask);
            return result;
        }),

        // 9. GrabCut – iterative foreground extraction using a bounding rectangle
        //    Returns a BGR image with only the extracted foreground (background = black).
        //    params: x (int, 0), y (int, 0),
        //            rectWidth (int, src.cols), rectHeight (int, src.rows),
        //            iterCount (int, 5)
        Map.entry("grabcut", (src, params) -> {
            int x    = ((Number) params.getOrDefault("x",           0         )).intValue();
            int y    = ((Number) params.getOrDefault("y",           0         )).intValue();
            int rw   = ((Number) params.getOrDefault("rectWidth",   src.cols())).intValue();
            int rh   = ((Number) params.getOrDefault("rectHeight",  src.rows())).intValue();
            int iter = ((Number) params.getOrDefault("iterCount",   5         )).intValue();
            // Clamp rect to image bounds
            x  = Math.max(0, x);   y  = Math.max(0, y);
            rw = Math.min(rw, src.cols() - x);
            rh = Math.min(rh, src.rows() - y);
            Mat bgr      = toColor(src);
            Mat mask     = new Mat(bgr.size(), CvType.CV_8UC1, new Scalar(0));
            Mat bgdModel = new Mat();
            Mat fgdModel = new Mat();
            Imgproc.grabCut(bgr, mask, new Rect(x, y, rw, rh),
                    bgdModel, fgdModel, iter, Imgproc.GC_INIT_WITH_RECT);
            // GC_FGD=1 (definite foreground), GC_PR_FGD=3 (probable foreground)
            Mat fgCertain  = new Mat();
            Mat fgProbable = new Mat();
            Core.compare(mask, new Scalar(1), fgCertain,  Core.CMP_EQ);
            Core.compare(mask, new Scalar(3), fgProbable, Core.CMP_EQ);
            Mat fgMask = new Mat();
            Core.add(fgCertain, fgProbable, fgMask);
            Mat result = Mat.zeros(bgr.size(), bgr.type());
            bgr.copyTo(result, fgMask);
            return result;
        })
    );

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (src, p) -> src)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Mat toGray(Mat src) {
        if (src.channels() == 1) return src;
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray,
                src.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
        return gray;
    }

    private static Mat toColor(Mat src) {
        if (src.channels() == 3) return src;
        Mat color = new Mat();
        Imgproc.cvtColor(src, color,
                src.channels() == 4 ? Imgproc.COLOR_BGRA2BGR : Imgproc.COLOR_GRAY2BGR);
        return color;
    }

    private static Mat toBinary(Mat src) {
        Mat gray   = toGray(src);
        Mat binary = new Mat();
        Imgproc.threshold(gray, binary, 0, 255,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
        return binary;
    }

    private static Mat bgrToHsv(Mat src) {
        Mat color = toColor(src);
        Mat hsv   = new Mat();
        Imgproc.cvtColor(color, hsv, Imgproc.COLOR_BGR2HSV);
        return hsv;
    }

    private static int thresholdType(String type) {
        return switch (type.toLowerCase()) {
            case "binary_inv" -> Imgproc.THRESH_BINARY_INV;
            case "trunc"      -> Imgproc.THRESH_TRUNC;
            case "tozero"     -> Imgproc.THRESH_TOZERO;
            case "tozero_inv" -> Imgproc.THRESH_TOZERO_INV;
            case "otsu"       -> Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU;
            case "triangle"   -> Imgproc.THRESH_BINARY + Imgproc.THRESH_TRIANGLE;
            default           -> Imgproc.THRESH_BINARY;
        };
    }
}
