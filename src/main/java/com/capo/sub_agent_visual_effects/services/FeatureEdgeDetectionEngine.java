package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.features2d.FastFeatureDetector;
import org.opencv.features2d.Features2d;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class FeatureEdgeDetectionEngine {

    // -----------------------------------------------------------------------
    // All OpenCV Feature & Edge Detection operations.
    // Keys are lower-cased so applyOperation(name.toLowerCase()) always hits.
    // All operations that require single-channel input auto-convert to grayscale.
    // Results are always returned as BGR for consistent pipeline chaining.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry = Map.ofEntries(

        // 1. Canny edge detector
        //    params: threshold1 (double, 50) – lower hysteresis threshold,
        //            threshold2 (double, 150) – upper hysteresis threshold,
        //            apertureSize (int, 3) – Sobel kernel size (3|5|7),
        //            L2gradient (boolean, false)
        Map.entry("canny", (src, params) -> {
            double  t1          = ((Number)  params.getOrDefault("threshold1",   50)).doubleValue();
            double  t2          = ((Number)  params.getOrDefault("threshold2",  150)).doubleValue();
            int     aperture    = ((Number)  params.getOrDefault("apertureSize",  3)).intValue();
            boolean l2gradient  = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("L2gradient", false)));
            Mat gray = toGray(src);
            Mat dst  = new Mat();
            Imgproc.Canny(gray, dst, t1, t2, aperture, l2gradient);
            return toColor(dst);
        }),

        // 2. Sobel gradient – highlights edges along X, Y, or both axes
        //    params: dx (int, 1) – order of X derivative (0 or 1),
        //            dy (int, 0) – order of Y derivative (0 or 1),
        //            ksize (int, 3) – Sobel kernel size (1|3|5|7),
        //            scale (double, 1.0), delta (double, 0.0)
        Map.entry("sobel", (src, params) -> {
            int    dx     = ((Number) params.getOrDefault("dx",     1  )).intValue();
            int    dy     = ((Number) params.getOrDefault("dy",     0  )).intValue();
            int    ksize  = ((Number) params.getOrDefault("ksize",  3  )).intValue();
            double scale  = ((Number) params.getOrDefault("scale",  1.0)).doubleValue();
            double delta  = ((Number) params.getOrDefault("delta",  0.0)).doubleValue();
            Mat    gray   = toGray(src);
            // If both dx and dy requested, compute and combine independently
            if (dx > 0 && dy > 0) {
                Mat gx = sobelAxis(gray, 1, 0, ksize, scale, delta);
                Mat gy = sobelAxis(gray, 0, 1, ksize, scale, delta);
                Mat combined = new Mat();
                Core.addWeighted(gx, 0.5, gy, 0.5, 0, combined);
                return toColor(combined);
            }
            return toColor(sobelAxis(gray, dx, dy, ksize, scale, delta));
        }),

        // 3. Scharr gradient – more accurate than Sobel for 3×3 kernels
        //    params: dx (int, 1), dy (int, 0),
        //            scale (double, 1.0), delta (double, 0.0)
        Map.entry("scharr", (src, params) -> {
            int    dx    = ((Number) params.getOrDefault("dx",    1  )).intValue();
            int    dy    = ((Number) params.getOrDefault("dy",    0  )).intValue();
            double scale = ((Number) params.getOrDefault("scale", 1.0)).doubleValue();
            double delta = ((Number) params.getOrDefault("delta", 0.0)).doubleValue();
            Mat    gray  = toGray(src);
            if (dx > 0 && dy > 0) {
                Mat gx = scharrAxis(gray, 1, 0, scale, delta);
                Mat gy = scharrAxis(gray, 0, 1, scale, delta);
                Mat combined = new Mat();
                Core.addWeighted(gx, 0.5, gy, 0.5, 0, combined);
                return toColor(combined);
            }
            return toColor(scharrAxis(gray, dx, dy, scale, delta));
        }),

        // 4. Laplacian – second-order derivative; detects edges in all directions
        //    params: ksize (int, 3, must be odd), scale (double, 1.0), delta (double, 0.0)
        Map.entry("laplacian", (src, params) -> {
            int    ksize = ((Number) params.getOrDefault("ksize",  3  )).intValue();
            double scale = ((Number) params.getOrDefault("scale",  1.0)).doubleValue();
            double delta = ((Number) params.getOrDefault("delta",  0.0)).doubleValue();
            Mat    gray  = toGray(src);
            Mat    lap   = new Mat();
            Imgproc.Laplacian(gray, lap, CvType.CV_16S, ksize, scale, delta);
            Mat dst = new Mat();
            Core.convertScaleAbs(lap, dst);
            return toColor(dst);
        }),

        // 5. Prewitt – edge detection using Prewitt horizontal and vertical kernels
        //    Applied via filter2D; no native OpenCV function.
        //    params: none
        Map.entry("prewitt", (src, params) -> {
            Mat gray = toGray(src);
            Mat kernelX = new Mat(3, 3, CvType.CV_64F);
            kernelX.put(0, 0, -1, 0, 1, -1, 0, 1, -1, 0, 1);
            Mat kernelY = new Mat(3, 3, CvType.CV_64F);
            kernelY.put(0, 0, 1, 1, 1, 0, 0, 0, -1, -1, -1);
            Mat gx = new Mat(), gy = new Mat();
            Imgproc.filter2D(gray, gx, CvType.CV_16S, kernelX);
            Imgproc.filter2D(gray, gy, CvType.CV_16S, kernelY);
            Mat absGx = new Mat(), absGy = new Mat();
            Core.convertScaleAbs(gx, absGx);
            Core.convertScaleAbs(gy, absGy);
            Mat dst = new Mat();
            Core.addWeighted(absGx, 0.5, absGy, 0.5, 0, dst);
            return toColor(dst);
        }),

        // 6. Probabilistic Hough line detection (HoughLinesP)
        //    Draws detected lines in red on a black canvas overlaid on the source.
        //    params: rho (double, 1.0) – distance resolution in pixels,
        //            theta (double, 1.0) – angle resolution in degrees,
        //            threshold (int, 80) – min vote count,
        //            minLineLength (double, 30.0),
        //            maxLineGap (double, 10.0),
        //            lineColor R/G/B (double, 0/0/255), lineThickness (int, 2)
        Map.entry("houghlines", (src, params) -> {
            double rho           = ((Number) params.getOrDefault("rho",           1.0 )).doubleValue();
            double thetaDeg      = ((Number) params.getOrDefault("theta",         1.0 )).doubleValue();
            int    threshold     = ((Number) params.getOrDefault("threshold",     80  )).intValue();
            double minLineLength = ((Number) params.getOrDefault("minLineLength", 30.0)).doubleValue();
            double maxLineGap    = ((Number) params.getOrDefault("maxLineGap",    10.0)).doubleValue();
            double lR            = ((Number) params.getOrDefault("lineColorR",    0   )).doubleValue();
            double lG            = ((Number) params.getOrDefault("lineColorG",    0   )).doubleValue();
            double lB            = ((Number) params.getOrDefault("lineColorB",    255 )).doubleValue();
            int    thickness     = ((Number) params.getOrDefault("lineThickness", 2   )).intValue();
            Mat    gray          = toGray(src);
            Mat    edges         = new Mat();
            Imgproc.Canny(gray, edges, 50, 150);
            Mat    lines = new Mat();
            Imgproc.HoughLinesP(edges, lines, rho, Math.toRadians(thetaDeg), threshold,
                    minLineLength, maxLineGap);
            Mat dst = toColor(src).clone();
            for (int i = 0; i < lines.rows(); i++) {
                double[] l = lines.get(i, 0);
                Imgproc.line(dst, new Point(l[0], l[1]), new Point(l[2], l[3]),
                        new Scalar(lB, lG, lR), thickness);
            }
            return dst;
        }),

        // 7. Hough circle detection
        //    Draws detected circles (circumference green, centre red) on the source.
        //    params: dp (double, 1.2) – inverse accumulator resolution ratio,
        //            minDist (double, 20.0) – min distance between circle centres,
        //            param1 (double, 100.0) – Canny upper threshold,
        //            param2 (double, 30.0) – accumulator threshold,
        //            minRadius (int, 0), maxRadius (int, 0 = auto)
        Map.entry("houghcircles", (src, params) -> {
            double dp        = ((Number) params.getOrDefault("dp",        1.2 )).doubleValue();
            double minDist   = ((Number) params.getOrDefault("minDist",   20.0)).doubleValue();
            double param1    = ((Number) params.getOrDefault("param1",   100.0)).doubleValue();
            double param2    = ((Number) params.getOrDefault("param2",    30.0)).doubleValue();
            int    minRadius = ((Number) params.getOrDefault("minRadius",  0   )).intValue();
            int    maxRadius = ((Number) params.getOrDefault("maxRadius",  0   )).intValue();
            Mat    gray      = toGray(src);
            Mat    blurred   = new Mat();
            Imgproc.GaussianBlur(gray, blurred, new org.opencv.core.Size(9, 9), 2);
            Mat circles = new Mat();
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT,
                    dp, minDist, param1, param2, minRadius, maxRadius);
            Mat dst = toColor(src).clone();
            for (int i = 0; i < circles.cols(); i++) {
                double[] c = circles.get(0, i);
                Imgproc.circle(dst, new Point(c[0], c[1]), (int) Math.round(c[2]),
                        new Scalar(0, 255, 0), 2);
                Imgproc.circle(dst, new Point(c[0], c[1]), 3,
                        new Scalar(0, 0, 255), -1);
            }
            return dst;
        }),

        // 8. Harris corner detector
        //    Normalises the response map, thresholds it, and draws circles at corners.
        //    params: blockSize (int, 2) – neighbourhood size,
        //            ksize (int, 3) – Sobel kernel aperture,
        //            k (double, 0.04) – Harris free parameter,
        //            thresh (int, 100) – response threshold [0,255]
        Map.entry("harriscorners", (src, params) -> {
            int    blockSize = ((Number) params.getOrDefault("blockSize", 2   )).intValue();
            int    ksize     = ((Number) params.getOrDefault("ksize",     3   )).intValue();
            double k         = ((Number) params.getOrDefault("k",         0.04)).doubleValue();
            int    thresh    = ((Number) params.getOrDefault("thresh",    100 )).intValue();
            Mat    gray      = toGray(src);
            Mat    gray32f   = new Mat();
            gray.convertTo(gray32f, CvType.CV_32F);
            Mat harrisResp = new Mat();
            Imgproc.cornerHarris(gray32f, harrisResp, blockSize, ksize, k);
            // Normalise to [0,255] and threshold to get corner mask
            Mat normResp = new Mat();
            Core.normalize(harrisResp, normResp, 0, 255, Core.NORM_MINMAX, CvType.CV_8U);
            Mat threshMask = new Mat();
            Imgproc.threshold(normResp, threshMask, thresh, 255, Imgproc.THRESH_BINARY);
            // Locate corner pixels and draw marks
            MatOfPoint locations = new MatOfPoint();
            Core.findNonZero(threshMask, locations);
            Mat dst = toColor(src).clone();
            for (Point p : locations.toArray()) {
                Imgproc.circle(dst, p, 5, new Scalar(0, 0, 255), 2);
            }
            return dst;
        }),

        // 9. Shi-Tomasi good features to track
        //    Draws green circles at each detected corner.
        //    params: maxCorners (int, 100),
        //            qualityLevel (double, 0.01),
        //            minDistance (double, 10.0),
        //            useHarris (boolean, false) – use Harris measure instead of min eigenvalue
        //            k (double, 0.04) – only used when useHarris=true
        Map.entry("shitomasi", (src, params) -> {
            int     maxCorners    = ((Number) params.getOrDefault("maxCorners",    100 )).intValue();
            double  qualityLevel  = ((Number) params.getOrDefault("qualityLevel",  0.01)).doubleValue();
            double  minDistance   = ((Number) params.getOrDefault("minDistance",   10.0)).doubleValue();
            boolean useHarris     = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("useHarris", false)));
            double  k             = ((Number) params.getOrDefault("k",             0.04)).doubleValue();
            Mat     gray          = toGray(src);
            MatOfPoint corners     = new MatOfPoint();
            Imgproc.goodFeaturesToTrack(gray, corners, maxCorners, qualityLevel, minDistance,
                    new Mat(), 3, useHarris, k);
            Mat dst = toColor(src).clone();
            for (Point p : corners.toArray()) {
                Imgproc.circle(dst, p, 5, new Scalar(0, 255, 0), 2);
            }
            return dst;
        }),

        // 10. Contour detection
        //     Finds and draws contours on a black canvas.
        //     params: retrieval (String, "external") – external|list|ccomp|tree,
        //             approximation (String, "simple") – none|simple|tc89_l1|tc89_kcos,
        //             minArea (double, 0.0) – filter contours smaller than this area,
        //             colorR/G/B (double, 0/255/0), thickness (int, 2)
        //             drawOnSource (boolean, false) – draw on source instead of black canvas
        Map.entry("contours", (src, params) -> {
            String retrieval    = String.valueOf(params.getOrDefault("retrieval",    "external"));
            String approx       = String.valueOf(params.getOrDefault("approximation","simple"));
            double minArea      = ((Number) params.getOrDefault("minArea",     0.0)).doubleValue();
            double cR           = ((Number) params.getOrDefault("colorR",      0  )).doubleValue();
            double cG           = ((Number) params.getOrDefault("colorG",      255)).doubleValue();
            double cB           = ((Number) params.getOrDefault("colorB",      0  )).doubleValue();
            int    thickness    = ((Number) params.getOrDefault("thickness",   2  )).intValue();
            boolean onSource    = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("drawOnSource", false)));
            Mat binary = toBinary(src);
            List<MatOfPoint> contours  = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binary, contours, hierarchy,
                    retrievalMode(retrieval), approximationMode(approx));
            Mat dst = onSource ? toColor(src).clone()
                               : Mat.zeros(src.size(), CvType.CV_8UC3);
            List<MatOfPoint> filtered = new ArrayList<>();
            for (MatOfPoint c : contours) {
                if (Imgproc.contourArea(c) >= minArea) filtered.add(c);
            }
            Imgproc.drawContours(dst, filtered, -1, new Scalar(cB, cG, cR), thickness);
            return dst;
        }),

        // 11. FAST keypoint detector – high-speed corner detection
        //     Draws detected keypoints as cyan circles.
        //     params: threshold (int, 10) – intensity difference threshold,
        //             nonmaxSuppression (boolean, true)
        Map.entry("fast", (src, params) -> {
            int     threshold  = ((Number) params.getOrDefault("threshold", 10)).intValue();
            boolean nonmax     = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("nonmaxSuppression", true)));
            Mat            gray      = toGray(src);
            MatOfKeyPoint  keypoints = new MatOfKeyPoint();
            FastFeatureDetector detector = FastFeatureDetector.create(threshold, nonmax);
            detector.detect(gray, keypoints);
            Mat dst = new Mat();
            Features2d.drawKeypoints(toColor(src), keypoints, dst,
                    new Scalar(255, 255, 0), Features2d.DrawMatchesFlags_DEFAULT);
            return dst;
        }),

        // 12. ORB keypoints – oriented and scale-invariant FAST + BRIEF descriptor detector
        //     Draws detected keypoints as red circles with orientation indicators.
        //     params: nFeatures (int, 500) – max number of features to retain,
        //             scaleFactor (float, 1.2f) – pyramid scale factor,
        //             nLevels (int, 8) – number of pyramid levels
        Map.entry("orb", (src, params) -> {
            int   nFeatures   = ((Number) params.getOrDefault("nFeatures",   500)).intValue();
            float scaleFactor = ((Number) params.getOrDefault("scaleFactor", 1.2f)).floatValue();
            int   nLevels     = ((Number) params.getOrDefault("nLevels",     8  )).intValue();
            Mat            gray      = toGray(src);
            MatOfKeyPoint  keypoints = new MatOfKeyPoint();
            ORB orbDetector = ORB.create(nFeatures, scaleFactor, nLevels);
            orbDetector.detect(gray, keypoints);
            Mat dst = new Mat();
            Features2d.drawKeypoints(toColor(src), keypoints, dst,
                    new Scalar(0, 0, 255), Features2d.DrawMatchesFlags_DRAW_RICH_KEYPOINTS);
            return dst;
        })
    );

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (src, p) -> src)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Returns a grayscale single-channel Mat regardless of input depth/channels. */
    private static Mat toGray(Mat src) {
        if (src.channels() == 1) return src;
        Mat dst = new Mat();
        Imgproc.cvtColor(src, dst,
                src.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
        return dst;
    }

    /** Ensures a 3-channel BGR Mat. */
    private static Mat toColor(Mat src) {
        if (src.channels() == 3) return src;
        Mat dst = new Mat();
        Imgproc.cvtColor(src, dst,
                src.channels() == 4 ? Imgproc.COLOR_BGRA2BGR : Imgproc.COLOR_GRAY2BGR);
        return dst;
    }

    /** Otsu-binarized single-channel Mat — reused by contours and segmentation. */
    private static Mat toBinary(Mat src) {
        Mat gray   = toGray(src);
        Mat binary = new Mat();
        Imgproc.threshold(gray, binary, 0, 255,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
        return binary;
    }

    /** Applies a single Sobel axis and converts to absolute 8-bit. */
    private static Mat sobelAxis(Mat gray, int dx, int dy, int ksize,
            double scale, double delta) {
        Mat grad = new Mat();
        Imgproc.Sobel(gray, grad, CvType.CV_16S, dx, dy, ksize, scale, delta);
        Mat abs = new Mat();
        Core.convertScaleAbs(grad, abs);
        return abs;
    }

    /** Applies Scharr on a single axis and converts to absolute 8-bit. */
    private static Mat scharrAxis(Mat gray, int dx, int dy,
            double scale, double delta) {
        Mat grad = new Mat();
        Imgproc.Scharr(gray, grad, CvType.CV_16S, dx, dy, scale, delta);
        Mat abs = new Mat();
        Core.convertScaleAbs(grad, abs);
        return abs;
    }

    private static int retrievalMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "list"   -> Imgproc.RETR_LIST;
            case "ccomp"  -> Imgproc.RETR_CCOMP;
            case "tree"   -> Imgproc.RETR_TREE;
            default       -> Imgproc.RETR_EXTERNAL;
        };
    }

    private static int approximationMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "none"       -> Imgproc.CHAIN_APPROX_NONE;
            case "tc89_l1"    -> Imgproc.CHAIN_APPROX_TC89_L1;
            case "tc89_kcos"  -> Imgproc.CHAIN_APPROX_TC89_KCOS;
            default           -> Imgproc.CHAIN_APPROX_SIMPLE;
        };
    }
}
