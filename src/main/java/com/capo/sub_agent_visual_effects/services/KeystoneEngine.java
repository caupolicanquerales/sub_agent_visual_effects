package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class KeystoneEngine {

    // -----------------------------------------------------------------------
    // Keystone (Projective Perspective) Distortion Engine
    //
    // Simulates the appearance of a flat document photographed from a non-
    // perpendicular angle — one edge appears closer (wider) and the opposite
    // edge appears farther (narrower), forming a trapezoid.
    //
    // Four mathematical models are composed into the full pipeline:
    //
    // ── 1. PROJECTIVE TRANSFORMATION (HOMOGRAPHY H) ───────────────────────
    //
    //   The entire distortion is captured by a 3×3 Homography matrix H that
    //   maps every source pixel (x, y) to a destination pixel (x', y'):
    //
    //     x' = (h₁₁x + h₁₂y + h₁₃) / (h₃₁x + h₃₂y + h₃₃)
    //     y' = (h₂₁x + h₂₂y + h₂₃) / (h₃₁x + h₃₂y + h₃₃)
    //
    //   H has 8 degrees of freedom and is uniquely determined by 4 point
    //   correspondences (src_rect → dst_trapezoid).  OpenCV solves the system
    //   via the Direct Linear Transform (DLT), which constructs the 8×9 matrix
    //   A from the point pairs and retrieves  h = nullspace(A)  via SVD.
    //
    // ── 2. CAMERA INTRINSIC MODEL (FOCAL LENGTH + PERSPECTIVE PROJECTION) ──
    //
    //   The 4 destination corners are derived from the physics of a pinhole
    //   camera with intrinsic matrix K:
    //
    //       K = [f   0   cx]       f  = focalLength × max(W, H)
    //           [0   f   cy]       c  = (W/2, H/2)   principal point
    //           [0   0    1]
    //
    //   The document is initially centred at depth f on the optical axis.
    //   After tilting by θ_y (top away) and θ_x (left away), the effective
    //   depth at each corner is:
    //
    //       depth(X, Y) = f·cos θ_y·cos θ_x
    //                    − Y·sin θ_y·cos θ_x
    //                    − X·sin θ_x·cos θ_y
    //
    //   where X = x − cx,  Y = y − cy  are centred coordinates.
    //
    //   This depth formula satisfies the user's width-shrinkage equation for
    //   the far edge:
    //
    //       w_new = w_old · f / (f·cos θ + z·sin θ)
    //
    //   with z = ±H/2 for the top/bottom edge.  The projected destination
    //   coordinates become:
    //
    //       dst_x = f · X·cos θ_x / depth(X, Y)  +  cx
    //       dst_y = f · Y·cos θ_y / depth(X, Y)  +  cy
    //
    // ── 3. GEOMETRIC WARPING — BACKWARD MAPPING + INTERPOLATION ────────────
    //
    //   OpenCV's warpPerspective implements Inverse Mapping to avoid the
    //   "forward hole" problem:
    //
    //       For every (x', y') in dst:  (x, y) = H⁻¹(x', y')
    //
    //   Since (x, y) is generally non-integer, bi-linear interpolation samples
    //   the 4 nearest source pixels:
    //
    //       f(x, y) ≈  Σᵢ Σⱼ  wᵢⱼ · I(xᵢ, yⱼ)
    //
    //   where wᵢⱼ are sub-pixel distance weights.  Lanczos-4 interpolation
    //   can be selected for sharper reconstruction at the cost of compute.
    //
    // ── 4. DEPTH OF FIELD — SPACE-VARIANT GAUSSIAN BLUR ────────────────────
    //
    //   A realistic photo taken at an angle has the far edge slightly out of
    //   focus.  The standard Gaussian kernel is:
    //
    //       G(x, y; σ) = 1/(2πσ²) · exp(−(x² + y²) / 2σ²)
    //
    //   The blur intensity σ is a linear function of distance from the focus
    //   line (near edge), modelling the "Plane of Focus":
    //
    //       σ(y) = dofStrength · |y − y_focus| / (rows − 1)   [vertical tilt]
    //       σ(x) = dofStrength · |x − x_focus| / (cols − 1)   [horizontal tilt]
    //
    //   Implementation: linear blend between the warped image and a fully
    //   blurred reference (σ = dofStrength) using a gradient α map:
    //
    //       pixel = warped · (1 − α)  +  blurred_max · α
    //       α  =  |y − y_focus| / (rows − 1)        [for vertical component]
    //
    //   The per-pixel products are computed via vectorised OpenCV operations
    //   (Core.multiply / Core.add on float Mats) — no Java pixel loops.
    //
    // -----------------------------------------------------------------------
    // params:
    //   tiltY         (double,  15.0) — vertical tilt in degrees; +→ top farther
    //   tiltX         (double,   0.0) — horizontal tilt in degrees; +→ left farther
    //   focalLength   (double,   1.5) — focal length as multiplier of max(W, H);
    //                                   higher = weaker perspective, lower = fishier
    //   dofEnabled    (boolean, true) — apply depth-of-field blur on the far edge
    //   dofStrength   (double,  12.0) — σ_max in pixels at the far edge
    //   interpolation (String, "linear") — "linear", "cubic", "lanczos4"
    //   borderMode    (String, "constant") — "constant", "replicate", "reflect"
    //   bgR/G/B       (int,     255)  — background fill colour (default white)
    // -----------------------------------------------------------------------

    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry =
        Map.of("keystone", (src, params) -> {

            double  tiltY       = ((Number) params.getOrDefault("tiltY",        15.0)).doubleValue();
            double  tiltX       = ((Number) params.getOrDefault("tiltX",         0.0)).doubleValue();
            double  focalMult   = ((Number) params.getOrDefault("focalLength",   1.5)).doubleValue();
            boolean dofEnabled  = Boolean.parseBoolean(
                                      String.valueOf(params.getOrDefault("dofEnabled", true)));
            double  dofStrength = ((Number) params.getOrDefault("dofStrength",  12.0)).doubleValue();
            String  interpStr   = (String)  params.getOrDefault("interpolation", "linear");
            String  borderStr   = (String)  params.getOrDefault("borderMode",    "constant");
            int     bgR         = ((Number) params.getOrDefault("bgR", 255)).intValue();
            int     bgG         = ((Number) params.getOrDefault("bgG", 255)).intValue();
            int     bgB         = ((Number) params.getOrDefault("bgB", 255)).intValue();

            int    rows    = src.rows();
            int    cols    = src.cols();
            double cx      = cols / 2.0;
            double cy      = rows / 2.0;
            double f       = focalMult * Math.max(cols, rows);

            double thetaY   = Math.toRadians(tiltY);
            double thetaX   = Math.toRadians(tiltX);
            double cosY     = Math.cos(thetaY);
            double sinY     = Math.sin(thetaY);
            double cosX     = Math.cos(thetaX);
            double sinX     = Math.sin(thetaX);
            double fCosYCosX = f * cosY * cosX;

            // ── STEP 1: Compute the 4 destination corners ─────────────────────
            //
            // Source corners in image coordinates (TL, TR, BR, BL).
            // Centred 3-D position: X = x − cx, Y = y − cy.
            //
            // Effective projection depth at each corner after combined tilt:
            //   depth = f·cosY·cosX  −  Y·sinY·cosX  −  X·sinX·cosY
            //
            // Sign convention:
            //   tiltY > 0  → top (Y < 0) gains larger depth → shrinks  ✓
            //   tiltX > 0  → left (X < 0) gains larger depth → shrinks ✓
            //
            // Projected destination:
            //   dst_x = f · X·cosX / depth + cx
            //   dst_y = f · Y·cosY / depth + cy
            double[][] srcCorners = {
                {0,        0       },   // TL
                {cols - 1, 0       },   // TR
                {cols - 1, rows - 1},   // BR
                {0,        rows - 1},   // BL
            };

            double[] dstXArr = new double[4];
            double[] dstYArr = new double[4];

            for (int i = 0; i < 4; i++) {
                double X     = srcCorners[i][0] - cx;
                double Y     = srcCorners[i][1] - cy;
                double depth = fCosYCosX - Y * sinY * cosX - X * sinX * cosY;
                // Guard against extremely large tilt producing near-zero or negative depth
                depth = Math.max(depth, f * 0.05);
                dstXArr[i] = f * X * cosX / depth + cx;
                dstYArr[i] = f * Y * cosY / depth + cy;
            }

            // ── STEP 2: Solve for H via DLT ───────────────────────────────────
            //
            // getPerspectiveTransform builds the 8×9 linear system  A·h = 0
            // from the 4 corner correspondences and retrieves the unique
            // solution via SVD, yielding the full 3×3 homography H.
            MatOfPoint2f srcPts = new MatOfPoint2f(
                new Point(srcCorners[0][0], srcCorners[0][1]),
                new Point(srcCorners[1][0], srcCorners[1][1]),
                new Point(srcCorners[2][0], srcCorners[2][1]),
                new Point(srcCorners[3][0], srcCorners[3][1])
            );
            MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(dstXArr[0], dstYArr[0]),
                new Point(dstXArr[1], dstYArr[1]),
                new Point(dstXArr[2], dstYArr[2]),
                new Point(dstXArr[3], dstYArr[3])
            );

            Mat H = Imgproc.getPerspectiveTransform(srcPts, dstPts);
            srcPts.release();
            dstPts.release();

            // ── STEP 3: Backward-map pixels via H⁻¹ + interpolation ───────────
            //
            // warpPerspective internally inverts H and performs:
            //   (x, y) = H⁻¹(x', y')  for every destination pixel (x', y')
            // Sub-pixel positions are resolved by bilinear (or Lanczos-4) kernel.
            int    interp  = interpolationFlag(interpStr);
            int    border  = borderMode(borderStr);
            Scalar bgColor = new Scalar(bgB, bgG, bgR);   // OpenCV channel order: BGR

            Mat warped = new Mat();
            Imgproc.warpPerspective(src, warped, H,
                                    new Size(cols, rows), interp, border, bgColor);
            H.release();

            // ── STEP 4: Space-variant Gaussian DoF blur ───────────────────────
            //
            // σ(y) = dofStrength · |y − y_focus| / (rows − 1)
            //   y_focus = near-edge row (bottom when tiltY > 0, top when tiltY < 0)
            //
            // Implemented as a vectorised blend:
            //   result = warped · (1 − α)  +  blurredMax · α
            // where α encodes the normalised distance from the focal line.
            boolean hasTiltY = Math.abs(tiltY) > 0.01;
            boolean hasTiltX = Math.abs(tiltX) > 0.01;

            if (!dofEnabled || dofStrength < 0.5 || (!hasTiltY && !hasTiltX)) {
                return warped;
            }

            // Max kernel size from σ_max (6σ rule), ensured odd and ≥ 3
            int maxKSize = Math.max(3, 2 * (int) Math.ceil(3.0 * dofStrength) + 1);
            if (maxKSize % 2 == 0) maxKSize++;

            // Fully blurred reference at σ = dofStrength (mapped to far-edge pixels)
            Mat fullyBlurred = new Mat();
            Imgproc.GaussianBlur(warped, fullyBlurred,
                                 new Size(maxKSize, maxKSize), dofStrength);

            // Build per-pixel α in [0, 1]: 0 = near (sharp), 1 = far (max blur).
            // Vertical and horizontal components are taken column/row-wise; the
            // final α = max(α_y, α_x) for combined tilts.
            Mat alphaMat = Mat.zeros(rows, cols, CvType.CV_32F);

            if (hasTiltY) {
                // Near edge: bottom when tiltY > 0 (top is far), top when tiltY < 0
                int focusRow = (tiltY > 0) ? rows - 1 : 0;
                Mat alphaY   = Mat.zeros(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    float val = (float)(Math.abs(y - focusRow) / (rows - 1.0));
                    alphaY.row(y).setTo(new Scalar(val));
                }
                Core.max(alphaMat, alphaY, alphaMat);
                alphaY.release();
            }

            if (hasTiltX) {
                // Near edge: right when tiltX > 0 (left is far), left when tiltX < 0
                int focusCol = (tiltX > 0) ? cols - 1 : 0;
                Mat alphaX   = Mat.zeros(rows, cols, CvType.CV_32F);
                for (int x = 0; x < cols; x++) {
                    float val = (float)(Math.abs(x - focusCol) / (cols - 1.0));
                    alphaX.col(x).setTo(new Scalar(val));
                }
                Core.max(alphaMat, alphaX, alphaMat);
                alphaX.release();
            }

            // Vectorised blend: result = warped·(1−α) + blurred·α
            // Single-channel alpha is replicated to match the image channel count
            // before element-wise multiply; avoids any per-pixel Java loop.
            int channels = warped.channels();

            Mat warpedF  = new Mat(); warped.convertTo(warpedF, CvType.CV_32F);
            Mat blurredF = new Mat(); fullyBlurred.convertTo(blurredF, CvType.CV_32F);

            // Build ones Mat for (1 − α)
            Mat ones     = new Mat(alphaMat.size(), CvType.CV_32F, Scalar.all(1.0));
            Mat invAlpha = new Mat();
            Core.subtract(ones, alphaMat, invAlpha);
            ones.release();

            // Replicate single-channel alpha/invAlpha to the image channel count
            Mat alpha3    = replicateToChannels(alphaMat, channels);
            Mat invAlpha3 = replicateToChannels(invAlpha, channels);

            Mat warpedW  = new Mat(); Core.multiply(warpedF,  invAlpha3, warpedW);
            Mat blurredW = new Mat(); Core.multiply(blurredF, alpha3,    blurredW);
            Mat resultF  = new Mat(); Core.add(warpedW, blurredW, resultF);

            Mat result = new Mat(); resultF.convertTo(result, src.type());

            // Release all intermediate Mats
            alphaMat.release();   invAlpha.release();
            alpha3.release();     invAlpha3.release();
            warpedF.release();    blurredF.release();
            warpedW.release();    blurredW.release();  resultF.release();
            fullyBlurred.release(); warped.release();

            return result;
        });

    // -----------------------------------------------------------------------
    // Replicates a single-channel CV_32F Mat across `n` channels via merge.
    // When n == 1 the original Mat is returned without copying.
    // -----------------------------------------------------------------------
    private static Mat replicateToChannels(Mat singleChannel, int n) {
        if (n == 1) return singleChannel.clone();
        List<Mat> channels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) channels.add(singleChannel);
        Mat merged = new Mat();
        Core.merge(channels, merged);
        return merged;
    }

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (s, p) -> s)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Helpers — map human-readable strings to OpenCV integer flags
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
