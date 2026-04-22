package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class GeometricTransformEngine {

    // -----------------------------------------------------------------------
    // All OpenCV Geometric Transformation operations.
    // Keys are lower-cased so applyTransform(name.toLowerCase()) always hits.
    // Each entry documents its accepted params, types, and defaults.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> transformRegistry = Map.ofEntries(

        // 1. Resize
        //    params: width (int, src.cols), height (int, src.rows),
        //            interpolation (String, "linear")
        Map.entry("resize", (src, params) -> {
            int width  = ((Number) params.getOrDefault("width",  src.cols())).intValue();
            int height = ((Number) params.getOrDefault("height", src.rows())).intValue();
            int interp = interpolationFlag((String) params.getOrDefault("interpolation", "linear"));
            Mat dst = new Mat();
            Imgproc.resize(src, dst, new Size(width, height), 0, 0, interp);
            return dst;
        }),

        // 2. Flip
        //    params: flipCode (int, 1) – 0=vertical axis, 1=horizontal axis, -1=both
        Map.entry("flip", (src, params) -> {
            int flipCode = ((Number) params.getOrDefault("flipCode", 1)).intValue();
            Mat dst = new Mat();
            Core.flip(src, dst, flipCode);
            return dst;
        }),

        // 3. Fixed-angle rotation (multiples of 90°)
        //    params: rotateCode (int, 0) – 0=90° CW, 1=180°, 2=90° CCW
        Map.entry("rotate", (src, params) -> {
            int rotateCode = ((Number) params.getOrDefault("rotateCode", 0)).intValue();
            Mat dst = new Mat();
            Core.rotate(src, dst, rotateCode);
            return dst;
        }),

        // 4. Arbitrary rotation around image centre
        //    params: angle (double degrees CCW, 45.0), scale (double, 1.0),
        //            interpolation (String, "linear"), borderMode (String, "constant")
        Map.entry("rotatearbitrary", (src, params) -> {
            double angle  = ((Number) params.getOrDefault("angle",  45.0)).doubleValue();
            double scale  = ((Number) params.getOrDefault("scale",   1.0)).doubleValue();
            int    interp = interpolationFlag((String) params.getOrDefault("interpolation", "linear"));
            int    border = borderMode((String) params.getOrDefault("borderMode", "constant"));
            Point  centre = new Point(src.cols() / 2.0, src.rows() / 2.0);
            Mat    M      = Imgproc.getRotationMatrix2D(centre, angle, scale);
            Mat    dst    = new Mat();
            Imgproc.warpAffine(src, dst, M, src.size(), interp, border, Scalar.all(0));
            return dst;
        }),

        // 5. Translation (pixel shift)
        //    params: tx (double pixels right, 0), ty (double pixels down, 0),
        //            borderMode (String, "constant")
        Map.entry("translate", (src, params) -> {
            double tx     = ((Number) params.getOrDefault("tx", 0)).doubleValue();
            double ty     = ((Number) params.getOrDefault("ty", 0)).doubleValue();
            int    border = borderMode((String) params.getOrDefault("borderMode", "constant"));
            Mat    M      = new Mat(2, 3, CvType.CV_64F);
            M.put(0, 0,  1, 0, tx,
                         0, 1, ty);
            Mat dst = new Mat();
            Imgproc.warpAffine(src, dst, M, src.size(), Imgproc.INTER_LINEAR, border, Scalar.all(0));
            return dst;
        }),

        // 6. Shear (skew along X or Y axis)
        //    params: shearX (double, 0.2), shearY (double, 0.0),
        //            borderMode (String, "constant")
        Map.entry("shear", (src, params) -> {
            double shearX = ((Number) params.getOrDefault("shearX", 0.2)).doubleValue();
            double shearY = ((Number) params.getOrDefault("shearY", 0.0)).doubleValue();
            int    border = borderMode((String) params.getOrDefault("borderMode", "constant"));
            // Affine shear matrix: |1  shX  0|
            //                      |shY  1  0|
            Mat M = new Mat(2, 3, CvType.CV_64F);
            M.put(0, 0,  1,      shearX, 0,
                         shearY, 1,      0);
            Mat dst = new Mat();
            Imgproc.warpAffine(src, dst, M, src.size(), Imgproc.INTER_LINEAR, border, Scalar.all(0));
            return dst;
        }),

        // 7. Generic affine warp – caller supplies the full 2×3 matrix
        //    params: m00 (1.0), m01 (0.0), m02 (0.0),
        //            m10 (0.0), m11 (1.0), m12 (0.0),
        //            outWidth (src.cols), outHeight (src.rows),
        //            interpolation (String, "linear"), borderMode (String, "constant")
        Map.entry("warpaffine", (src, params) -> {
            double m00 = ((Number) params.getOrDefault("m00", 1.0)).doubleValue();
            double m01 = ((Number) params.getOrDefault("m01", 0.0)).doubleValue();
            double m02 = ((Number) params.getOrDefault("m02", 0.0)).doubleValue();
            double m10 = ((Number) params.getOrDefault("m10", 0.0)).doubleValue();
            double m11 = ((Number) params.getOrDefault("m11", 1.0)).doubleValue();
            double m12 = ((Number) params.getOrDefault("m12", 0.0)).doubleValue();
            int outW   = ((Number) params.getOrDefault("outWidth",  src.cols())).intValue();
            int outH   = ((Number) params.getOrDefault("outHeight", src.rows())).intValue();
            int interp = interpolationFlag((String) params.getOrDefault("interpolation", "linear"));
            int border = borderMode((String) params.getOrDefault("borderMode", "constant"));
            Mat M = new Mat(2, 3, CvType.CV_64F);
            M.put(0, 0, m00, m01, m02,
                        m10, m11, m12);
            Mat dst = new Mat();
            Imgproc.warpAffine(src, dst, M, new Size(outW, outH), interp, border, Scalar.all(0));
            return dst;
        }),

        // 8. Perspective (homography) warp – caller supplies the full 3×3 matrix
        //    params: m00..m22 (identity defaults),
        //            outWidth (int, auto), outHeight (int, auto) – used as lower bounds;
        //            the canvas is auto-expanded so no warped corner is clipped.
        //            interpolation (String, "linear"), borderMode (String, "constant")
        //    Fix – Edge Padding (canvas problem):
        //    All 4 source corners are projected through H to compute the true
        //    bounding box of the warped document.  A translation matrix T is then
        //    prepended (H_adj = T·H) so that the top-left corner of the bounding
        //    box lands at (0,0) in the output, and the canvas is sized accordingly.
        Map.entry("warpperspective", (src, params) -> {
            double m00 = ((Number) params.getOrDefault("m00", 1.0)).doubleValue();
            double m01 = ((Number) params.getOrDefault("m01", 0.0)).doubleValue();
            double m02 = ((Number) params.getOrDefault("m02", 0.0)).doubleValue();
            double m10 = ((Number) params.getOrDefault("m10", 0.0)).doubleValue();
            double m11 = ((Number) params.getOrDefault("m11", 1.0)).doubleValue();
            double m12 = ((Number) params.getOrDefault("m12", 0.0)).doubleValue();
            double m20 = ((Number) params.getOrDefault("m20", 0.0)).doubleValue();
            double m21 = ((Number) params.getOrDefault("m21", 0.0)).doubleValue();
            double m22 = ((Number) params.getOrDefault("m22", 1.0)).doubleValue();
            int interp = interpolationFlag((String) params.getOrDefault("interpolation", "linear"));
            // Default to BORDER_REPLICATE: when perspective projection places corners
            // outside the source canvas, replicating the nearest edge pixel prevents
            // the white-constant-fill "empty space" tear artefact at the corners.
            int border = borderMode((String) params.getOrDefault("borderMode", "replicate"));

            // Project the 4 source corners through H to find the output bounding box
            double[][] srcCorners = {
                {0,               0              },
                {src.cols() - 1,  0              },
                {src.cols() - 1,  src.rows() - 1 },
                {0,               src.rows() - 1 }
            };
            double minX = Double.MAX_VALUE,  minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] c : srcCorners) {
                double wx = m00 * c[0] + m01 * c[1] + m02;
                double wy = m10 * c[0] + m11 * c[1] + m12;
                double w  = m20 * c[0] + m21 * c[1] + m22;
                if (Math.abs(w) < 1e-10) continue;   // degenerate point – skip
                double px = wx / w;
                double py = wy / w;
                minX = Math.min(minX, px);  maxX = Math.max(maxX, px);
                minY = Math.min(minY, py);  maxY = Math.max(maxY, py);
            }
            // Canvas large enough to hold the full warped document
            int canvasW = (int) Math.ceil(maxX - minX) + 1;
            int canvasH = (int) Math.ceil(maxY - minY) + 1;
            // Respect any explicit lower-bound the caller passed
            canvasW = Math.max(canvasW, ((Number) params.getOrDefault("outWidth",  canvasW)).intValue());
            canvasH = Math.max(canvasH, ((Number) params.getOrDefault("outHeight", canvasH)).intValue());

            // Build H_adj = T · H  where T translates by (-minX, -minY)
            // so the top-left warped corner maps to (0, 0) on the canvas
            Mat H = new Mat(3, 3, CvType.CV_64F);
            H.put(0, 0, m00, m01, m02,
                        m10, m11, m12,
                        m20, m21, m22);
            Mat T = Mat.eye(3, 3, CvType.CV_64F);
            T.put(0, 2, -minX);
            T.put(1, 2, -minY);
            Mat H_adj = new Mat();
            Core.gemm(T, H, 1.0, new Mat(), 0.0, H_adj);
            T.release();
            H.release();

            Mat dst = new Mat();
            Imgproc.warpPerspective(src, dst, H_adj, new Size(canvasW, canvasH), interp, border, Scalar.all(0));
            H_adj.release();
            return dst;
        }),

        // 9. Extract a rectangular patch at sub-pixel accuracy
        //    params: centerX (double, src.cols/2), centerY (double, src.rows/2),
        //            patchWidth (int, 64), patchHeight (int, 64)
        Map.entry("getrectsub", (src, params) -> {
            double cx = ((Number) params.getOrDefault("centerX", src.cols() / 2.0)).doubleValue();
            double cy = ((Number) params.getOrDefault("centerY", src.rows() / 2.0)).doubleValue();
            int    pw = ((Number) params.getOrDefault("patchWidth",  64)).intValue();
            int    ph = ((Number) params.getOrDefault("patchHeight", 64)).intValue();
            Mat    dst = new Mat();
            Imgproc.getRectSubPix(src, new Size(pw, ph), new Point(cx, cy), dst);
            return dst;
        }),

        // 10. Linear polar transform – maps image to/from polar coordinates
        //     params: centerX (double, src.cols/2), centerY (double, src.rows/2),
        //             maxRadius (double, half-diagonal), interpolation (String, "linear"),
        //             inverse (boolean, false)
        Map.entry("linearpolar", (src, params) -> {
            double cx        = ((Number) params.getOrDefault("centerX",   src.cols() / 2.0)).doubleValue();
            double cy        = ((Number) params.getOrDefault("centerY",   src.rows() / 2.0)).doubleValue();
            double maxRadius = ((Number) params.getOrDefault("maxRadius",
                    Math.hypot(src.cols(), src.rows()) / 2.0)).doubleValue();
            int    interp    = interpolationFlag((String) params.getOrDefault("interpolation", "linear"));
            boolean inverse  = Boolean.parseBoolean(String.valueOf(params.getOrDefault("inverse", false)));
            // WARP_POLAR_LINEAR = 0; OR with WARP_INVERSE_MAP when reversing
            int flags = interp | (inverse ? Imgproc.WARP_INVERSE_MAP : 0);
            Mat dst = new Mat();
            Imgproc.warpPolar(src, dst, src.size(), new Point(cx, cy), maxRadius, flags);
            return dst;
        }),

        // 11. Log-polar transform – scale-invariant polar representation
        //     params: centerX (double, src.cols/2), centerY (double, src.rows/2),
        //             maxRadius (double, half-diagonal), interpolation (String, "linear"),
        //             inverse (boolean, false)
        Map.entry("logpolar", (src, params) -> {
            double cx        = ((Number) params.getOrDefault("centerX",   src.cols() / 2.0)).doubleValue();
            double cy        = ((Number) params.getOrDefault("centerY",   src.rows() / 2.0)).doubleValue();
            double maxRadius = ((Number) params.getOrDefault("maxRadius",
                    Math.hypot(src.cols(), src.rows()) / 2.0)).doubleValue();
            int    interp    = interpolationFlag((String) params.getOrDefault("interpolation", "linear"));
            boolean inverse  = Boolean.parseBoolean(String.valueOf(params.getOrDefault("inverse", false)));
            int flags = interp | Imgproc.WARP_POLAR_LOG | (inverse ? Imgproc.WARP_INVERSE_MAP : 0);
            Mat dst = new Mat();
            Imgproc.warpPolar(src, dst, src.size(), new Point(cx, cy), maxRadius, flags);
            return dst;
        }),

        // 12. Barrel / pincushion radial distortion via remap
        //     params: k1 (double, 0.01 – positive=barrel, negative=pincushion;
        //                         keep in [0.001, 0.02] to prevent geometry collapse),
        //             k2 (double, 0.0 – higher-order Brown-Conrady correction)
        //
        //     Fix 1 – Inverse Mapping:
        //       For every destination pixel (x_dst, y_dst) we compute the source
        //       (undistorted) coordinate via x_src = x_dst / (1 + k1·r² + k2·r⁴).
        //       This avoids forward-mapping holes where destination pixels are skipped.
        //
        //     Fix 2 – Radial Distortion Bound Check:
        //       r is normalised to [0,1] using the half-diagonal of the image so that
        //       corner pixels reach r=1 and no pixel ever exceeds it.  The denominator
        //       (1 + k1·r² + k2·r⁴) is guarded against ≤ 0 to prevent geometry
        //       collapse when aggressive k coefficients are supplied.
        Map.entry("remap", (src, params) -> {
            double k1   = ((Number) params.getOrDefault("k1", 0.01)).doubleValue();
            double k2   = ((Number) params.getOrDefault("k2", 0.0)).doubleValue();
            int    cols = src.cols();
            int    rows = src.rows();
            double cx        = cols / 2.0;
            double cy        = rows / 2.0;
            // Normalise r to [0,1] over the half-diagonal so corner pixels reach r=1
            double maxRadius = Math.hypot(cols, rows) / 2.0;
            Mat mapX = new Mat(rows, cols, CvType.CV_32F);
            Mat mapY = new Mat(rows, cols, CvType.CV_32F);
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    double dx    = x - cx;
                    double dy    = y - cy;
                    // r normalised to [0,1]: keeps denominator near 1 at the centre
                    double rNorm = Math.hypot(dx, dy) / maxRadius;
                    double r2    = rNorm * rNorm;
                    // Inverse-mapping: x_src = x_dst / (1 + k1·r² + k2·r⁴)
                    double denom  = 1.0 + k1 * r2 + k2 * r2 * r2;
                    // Safety guard: collapse / inversion prevention
                    if (denom <= 0.0) denom = 1.0;
                    double factor = 1.0 / denom;
                    // Clamp to valid pixel range — edge replication prevents
                    // BORDER_CONSTANT white/black fill from appearing at the
                    // outer corners when strong k1 values push the map OOB.
                    float xSrc = (float) Math.max(0.0, Math.min(cols - 1, cx + dx * factor));
                    float ySrc = (float) Math.max(0.0, Math.min(rows - 1, cy + dy * factor));
                    mapX.put(y, x, xSrc);
                    mapY.put(y, x, ySrc);
                }
            }
            Mat dst = new Mat();
            Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR,
                          Core.BORDER_REPLICATE, Scalar.all(255));
            return dst;
        }),

        // 13. Lens undistortion – removes radial + tangential camera distortion
        //     params: fx (double, src.cols), fy (double, src.rows),
        //             cx (double, src.cols/2), cy (double, src.rows/2),
        //             k1, k2, p1, p2 (double distortion coefficients, all 0.0)
        Map.entry("undistort", (src, params) -> {
            double fx  = ((Number) params.getOrDefault("fx", (double) src.cols())).doubleValue();
            double fy  = ((Number) params.getOrDefault("fy", (double) src.rows())).doubleValue();
            double pcx = ((Number) params.getOrDefault("cx", src.cols() / 2.0)).doubleValue();
            double pcy = ((Number) params.getOrDefault("cy", src.rows() / 2.0)).doubleValue();
            double k1  = ((Number) params.getOrDefault("k1", 0.0)).doubleValue();
            double k2  = ((Number) params.getOrDefault("k2", 0.0)).doubleValue();
            double p1  = ((Number) params.getOrDefault("p1", 0.0)).doubleValue();
            double p2  = ((Number) params.getOrDefault("p2", 0.0)).doubleValue();
            Mat camMatrix = Mat.eye(3, 3, CvType.CV_64F);
            camMatrix.put(0, 0,  fx,   0, pcx,
                                  0,  fy, pcy,
                                  0,   0,   1);
            Mat distCoeffs = new Mat(1, 4, CvType.CV_64F);
            distCoeffs.put(0, 0, k1, k2, p1, p2);
            Mat dst = new Mat();
            Calib3d.undistort(src, dst, camMatrix, distCoeffs);
            return dst;
        }),

        // 14. Deskew – auto-detects skew angle and corrects it via affine rotation
        //     params: borderMode (String, "replicate" – fills newly exposed edges),
        //             maxAngle  (double, 45.0 – corrections larger than this are skipped)
        Map.entry("deskew", (src, params) -> {
            int    border   = borderMode((String) params.getOrDefault("borderMode", "replicate"));
            double maxAngle = ((Number) params.getOrDefault("maxAngle", 45.0)).doubleValue();

            // 1. Produce a binary mask to locate foreground pixels
            Mat gray = new Mat();
            if (src.channels() == 1) {
                gray = src.clone();
            } else {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            }
            Mat thresh = new Mat();
            Imgproc.threshold(gray, thresh, 0, 255,
                    Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
            gray.release();

            // 2. Collect all foreground pixel coordinates
            Mat coords = new Mat();
            Core.findNonZero(thresh, coords);
            thresh.release();

            if (coords.empty()) {
                coords.release();
                return src.clone();   // blank image – nothing to correct
            }

            // 3. Fit a minimum-area rectangle to infer the dominant text angle
            MatOfPoint2f pts = new MatOfPoint2f(coords.reshape(2, coords.rows()));
            coords.release();
            RotatedRect box  = Imgproc.minAreaRect(pts);
            pts.release();

            // minAreaRect returns angle in (-90, 0]; when width < height the box
            // is portrait-oriented, so add 90° to get the true tilt.
            double angle = box.angle;
            if (box.size.width < box.size.height) angle += 90.0;

            // Skip correction if the detected deviation exceeds the safety cap
            if (Math.abs(angle) > maxAngle) return src.clone();

            // 4. Build a pure-rotation affine matrix and warp
            Point centre = new Point(src.cols() / 2.0, src.rows() / 2.0);
            Mat   M      = Imgproc.getRotationMatrix2D(centre, angle, 1.0);
            Mat   dst    = new Mat();
            Imgproc.warpAffine(src, dst, M, src.size(),
                    Imgproc.INTER_LINEAR, border, Scalar.all(0));
            M.release();
            return dst;
        })
    );

    public Mat applyTransform(String transformName, Mat input, Map<String, Object> params) {
        return transformRegistry.getOrDefault(transformName.toLowerCase(), (src, p) -> src)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Helpers – map human-readable strings to OpenCV integer flags
    // -----------------------------------------------------------------------

    private static int interpolationFlag(String name) {
        if (name == null) return Imgproc.INTER_LINEAR;
        return switch (name.toLowerCase()) {
            case "nearest"  -> Imgproc.INTER_NEAREST;
            case "cubic"    -> Imgproc.INTER_CUBIC;
            case "area"     -> Imgproc.INTER_AREA;
            case "lanczos4" -> Imgproc.INTER_LANCZOS4;
            default         -> Imgproc.INTER_LINEAR;
        };
    }

    private static int borderMode(String name) {
        if (name == null) return Core.BORDER_CONSTANT;
        return switch (name.toLowerCase()) {
            case "replicate"          -> Core.BORDER_REPLICATE;
            case "reflect"            -> Core.BORDER_REFLECT;
            case "reflect101", "wrap" -> Core.BORDER_REFLECT_101;
            default                   -> Core.BORDER_CONSTANT;
        };
    }
}
