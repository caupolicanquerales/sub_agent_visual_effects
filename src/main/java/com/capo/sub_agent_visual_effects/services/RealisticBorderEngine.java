package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class RealisticBorderEngine {

    // -----------------------------------------------------------------------
    // Realistic border engine — Explicit Boundary Map via Image Segmentation.
    //
    // The most mathematically robust border model is derived from segmenting the
    // actual paper/document region in the image and computing its Convex Hull:
    //
    //   H = CHull( P )   where   P = { (x,y) | M_refined(x,y) = 1 }
    //
    // The hull H is a sorted vertex sequence [V₁, V₂, … Vₙ] representing the
    // tightest convex polygon enclosing all detected paper pixels.  A per-pixel
    // distance map d(x,y) = dist(pixel, H) is then used to drive a Perlin-noise
    // perturbed smooth-step blend, producing organic paper-fibre geometry at the
    // boundary rather than a mathematically perfect smooth envelope.
    //
    // Five-stage pipeline
    // ───────────────────
    // 1. GRAYSCALE LUMINANCE
    //      L(x,y) = 0.299·I_R + 0.587·I_G + 0.114·I_B
    //    Collapses the RGB image to a single luminance channel, removing
    //    redundant colour information while retaining the contrast that
    //    separates paper from background.
    //
    // 2. VARIANCE-BASED ADAPTIVE THRESHOLDING
    //      M(x,y) = 1  if  L(x,y) > mean( L_{N(x,y)} ) − C
    //               0  otherwise
    //    A global Otsu threshold fails here because the paper contains large
    //    dark regions (stains, text) and the surrounding background is bright.
    //    An adaptive threshold based on the local neighbourhood mean N(x,y)
    //    exploits the fact that the background is uniform (low local variance)
    //    while the paper interior is textured (high local variance).  The
    //    constant C shifts the boundary sensitivity.
    //
    // 3. MORPHOLOGICAL REFINEMENT
    //      M_clean   = (M ⊖ S) ⊕ S      [Opening  – removes background specks]
    //      M_refined = (M_clean ⊕ S) ⊖ S [Closing  – fills holes inside paper]
    //    The structuring element S is an elliptical disc of configurable
    //    diameter.  Opening first removes isolated foreground noise dots in the
    //    background, then Closing fills the bright paper areas that appear black
    //    due to dark stains or text content.
    //
    // 4. EXPLICIT COORDINATE MAP — CONVEX HULL MODEL
    //      P = { (x,y) | M_refined(x,y) = 1 }
    //      H = CHull(P)
    //    The paper set P is obtained from the largest external contour of
    //    M_refined.  The Convex Hull of that contour is the mathematical model
    //    H for the border.  H smooths the jagged per-pixel mask boundary into
    //    a usable piecewise-linear polygon described by n hull vertices.
    //
    // 5. DISTANCE-WEIGHTED FEATHERED COMPOSITE
    //      d(x,y) = distance from pixel (x,y) to the nearest point on H
    //
    //    • d = 0           → outside H  → replace pixel with background colour
    //    • 0 < d < F       → feather zone
    //                          t     = d / F                     ∈ [0,1]
    //                          noise = Perlin2D(x·s, y·s)       ∈ [−1,1]
    //                          t′    = clamp(t + k·noise)       ∈ [0,1]
    //                          blend = t′²(3 − 2t′)            (smooth-step)
    //                          pixel = bg·(1−blend) + src·blend
    //    • d ≥ F           → fully inside H → keep original pixel unchanged
    //
    //    The Perlin noise term k·noise perturbs the iso-distance contours,
    //    converting the mathematically uniform feather envelope into irregular
    //    paper-fibre geometry that is indistinguishable from a physically torn
    //    or worn edge.
    // -----------------------------------------------------------------------

    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry = Map.ofEntries(

        // segmentborder — full pipeline: detect paper boundary via adaptive
        //   thresholding + morphological refinement + convex hull, then render
        //   a feathered Perlin-noise border along the detected hull boundary.
        //
        // params:
        //   blockSize   (int,    25)  — adaptive threshold neighbourhood size
        //                              (the window is blockSize×blockSize; must
        //                               be an odd number ≥ 3)
        //   adaptiveC   (int,    10)  — constant C subtracted from local mean;
        //                              higher value = thinner paper mask
        //   morphKernel (int,    15)  — diameter of the elliptical structuring
        //                              element used for Opening and Closing
        //   featherPx   (int,    20)  — width of the feather/blend zone in
        //                              pixels measured inward from the hull
        //   fiberNoise  (double, 0.4) — amplitude of Perlin fibre-texture noise
        //                              in the blend zone; 0 = smooth gradient,
        //                              1 = maximum organic irregularity
        //   bgR         (int,   255)  — background replacement colour, red
        //   bgG         (int,   255)  — background replacement colour, green
        //   bgB         (int,   255)  — background replacement colour, blue
        //   seed        (int,    -1)  — RNG seed for noise; −1 = truly random
        //   hullExpand  (int,    55)  — number of pixels to dilate the hull mask
        //                              outward before computing the distance
        //                              transform.  When wornedge darkens the
        //                              outer band (L≈120–170), the threshold
        //                              excludes that band → hull sits too far
        //                              inside real content.  Expanding the hull
        //                              by ≥ worn-edge depth pushes the cutoff
        //                              back to the true image boundary so the
        //                              feather zone starts right at the paper
        //                              edge rather than well inside content.
        //                              Default 55 matches the default wornedge
        //                              depth so it is safe to call with or
        //                              without a prior wornedge pass.  For a
        //                              fresh image (no prior wornedge) the
        //                              dilation is clamped to the image bounds
        //                              and has no visible effect.  Set to 0
        //                              only when the image has never had a
        //                              wornedge pass applied.
        Map.entry("segmentborder", (src, params) -> {

            int    blockSize   = ((Number) params.getOrDefault("blockSize",   25  )).intValue();
            int    adaptiveC   = ((Number) params.getOrDefault("adaptiveC",   10  )).intValue();
            int    morphKernel = ((Number) params.getOrDefault("morphKernel", 15  )).intValue();
            int    featherPx   = ((Number) params.getOrDefault("featherPx",   20  )).intValue();
            double fiberNoise  = ((Number) params.getOrDefault("fiberNoise",  0.4 )).doubleValue();
            int    bgR         = ((Number) params.getOrDefault("bgR",        255  )).intValue();
            int    bgG         = ((Number) params.getOrDefault("bgG",        255  )).intValue();
            int    bgB         = ((Number) params.getOrDefault("bgB",        255  )).intValue();
            int    seed        = ((Number) params.getOrDefault("seed",        -1  )).intValue();
            int    hullExpand  = ((Number) params.getOrDefault("hullExpand",  55  )).intValue();

            // Parameter sanitisation
            if (blockSize < 3)      blockSize = 3;
            if (blockSize % 2 == 0) blockSize += 1;   // adaptive threshold requires odd block
            if (morphKernel < 1)    morphKernel = 1;
            if (featherPx < 1)      featherPx   = 1;
            if (hullExpand < 0)     hullExpand  = 0;
            // Cap feather zone so it cannot exceed one quarter of the smallest dimension
            featherPx = Math.min(featherPx, Math.min(src.rows(), src.cols()) / 4);

            Random rng  = (seed >= 0) ? new Random(seed) : new Random();
            int    rows = src.rows();
            int    cols = src.cols();
            int    ch   = src.channels();

            // ----------------------------------------------------------------
            // Stage 1 — Grayscale luminance
            //   L(x,y) = 0.299·I_R + 0.587·I_G + 0.114·I_B
            // ----------------------------------------------------------------
            Mat gray = new Mat();
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

            // ----------------------------------------------------------------
            // Stage 2 — Global-mean threshold
            //   threshold = global_mean(L) − adaptiveC
            //   M(x,y) = 1  if  L > threshold,  0 otherwise
            //
            // NOTE: ADAPTIVE_THRESH_MEAN_C is intentionally NOT used here.
            // After a wornedge pass the amber/brown edge zone is *uniformly*
            // tinted (L ≈ 120–170 at depth 55, strength 0.78) while the paper
            // interior is bright (L ≈ 240–250).  In any uniform-luminance
            // region local_mean ≈ pixel_value, so the condition
            //   pixel > local_mean − C
            // reduces to  C > 0  → always TRUE regardless of the actual
            // luminance level.  Every worn-edge pixel is therefore labeled 255
            // (paper), the hull collapses to the full image boundary, and the
            // feather zone shrinks to a barely visible rim at the image edge.
            //
            // Using the GLOBAL mean as the reference corrects this:
            //   global_mean ≈ 220–235 (image mostly bright parchment)
            //   threshold   ≈ global_mean − adaptiveC  (≈ 210–225)
            //   worn edge   L ≈ 120–170  → below threshold → 0 (background) ✓
            //   paper body  L ≈ 240–250  → above threshold → 255 (paper)    ✓
            // ----------------------------------------------------------------
            Mat mask = new Mat();
            org.opencv.core.Scalar meanScalar = Core.mean(gray);
            double globalMean = meanScalar.val[0];
            double thrVal     = Math.max(0.0, globalMean - adaptiveC);
            Imgproc.threshold(gray, mask, thrVal, 255, Imgproc.THRESH_BINARY);

            // ----------------------------------------------------------------
            // Stage 3 — Morphological refinement
            //   Structuring element: elliptical disc of diameter morphKernel
            //   Opening:  M_clean   = (M ⊖ S) ⊕ S   — noise removal
            //   Closing:  M_refined = (M_clean ⊕ S) ⊖ S — hole filling
            // ----------------------------------------------------------------
            Mat kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    new Size(morphKernel, morphKernel));

            Mat maskOpen    = new Mat();
            Imgproc.morphologyEx(mask,     maskOpen,    Imgproc.MORPH_OPEN,  kernel);
            Mat maskRefined = new Mat();
            Imgproc.morphologyEx(maskOpen, maskRefined, Imgproc.MORPH_CLOSE, kernel);

            // ----------------------------------------------------------------
            // Stage 4 — Contour extraction and Convex Hull model
            //   P = { (x,y) | M_refined(x,y) = 1 }
            //   H = CHull(P)
            // ----------------------------------------------------------------
            List<MatOfPoint> contours  = new ArrayList<>();
            Mat              hierarchy = new Mat();
            Imgproc.findContours(maskRefined, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            if (contours.isEmpty()) return src.clone();

            // Select the largest contour by area — this is the main paper body
            MatOfPoint largest = contours.get(0);
            double     maxArea = Imgproc.contourArea(largest);
            for (MatOfPoint c : contours) {
                double a = Imgproc.contourArea(c);
                if (a > maxArea) {
                    maxArea = a;
                    largest = c;
                }
            }

            // Convex Hull: hullIdx contains indices into `largest` pointing to
            // the vertex subset that forms the hull polygon H = [V₁ … Vₙ]
            MatOfInt hullIdx = new MatOfInt();
            Imgproc.convexHull(largest, hullIdx);

            // Reconstruct hull as a MatOfPoint for fillPoly
            Point[]    contourPts  = largest.toArray();
            int[]      idxArr      = hullIdx.toArray();
            Point[]    hullPtsArr  = new Point[idxArr.length];
            for (int i = 0; i < idxArr.length; i++) {
                hullPtsArr[i] = contourPts[idxArr[i]];
            }
            MatOfPoint hullContour = new MatOfPoint(hullPtsArr);

            // Filled hull mask: 255 inside the convex boundary H, 0 outside
            Mat hullMask = Mat.zeros(rows, cols, CvType.CV_8UC1);
            List<MatOfPoint> hullList = new ArrayList<>();
            hullList.add(hullContour);
            Imgproc.fillPoly(hullMask, hullList, new Scalar(255));

            // ----------------------------------------------------------------
            // Hull expansion — dilate the hull mask outward by hullExpand px.
            //
            // After a wornedge pass the amber/brown zone is classified as
            // background by Stage 2 (correct by design), so the hull boundary
            // sits at the inner edge of the worn zone — well inside the real
            // paper.  Expanding the hull pushes the cutoff back toward the
            // actual image edge so the feather zone starts AT the paper
            // boundary, not deep inside content.
            //
            //   hullMask_expanded = hullMask ⊕ D   (dilation)
            //   D = elliptical disc of diameter (2·hullExpand + 1)
            //   Result is clamped to image bounds by OpenCV automatically.
            // ----------------------------------------------------------------
            if (hullExpand > 0) {
                int    expandDiam   = 2 * hullExpand + 1;
                Mat    expandKernel = Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE,
                        new Size(expandDiam, expandDiam));
                Mat hullMaskExpanded = new Mat();
                Imgproc.dilate(hullMask, hullMaskExpanded, expandKernel);
                hullMask = hullMaskExpanded;
            }

            // ----------------------------------------------------------------
            // Stage 5 — Distance transform and feathered composite
            //
            //   distanceTransform on hullMask (CV_8UC1, 255 inside, 0 outside):
            //     • interior pixels  → Euclidean distance to nearest 0-pixel
            //     • exterior pixels  → 0  (they are themselves 0 in the mask)
            //
            //   d(x,y) therefore measures how far each pixel is from the hull
            //   boundary, giving us a scalar field to drive the blend.
            // ----------------------------------------------------------------
            Mat distInside = new Mat();
            Imgproc.distanceTransform(hullMask, distInside, Imgproc.DIST_L2, 5);
            // distInside is CV_32FC1

            // Perlin permutation table — drives organic fibre noise in blend zone
            int[]  perm       = buildPermTable(rng);
            // Spatial frequency of fibre noise (independent of image resolution)
            double noiseScale = 0.05;

            // Random offsets into Perlin noise space — each call starts from a
            // different position in the infinite noise field so the organic bump
            // pattern appears at genuinely different locations along the hull
            // boundary even when the document (and therefore the hull itself)
            // is identical across calls.
            double noiseOffsetX = rng.nextDouble() * 500.0;
            double noiseOffsetY = rng.nextDouble() * 500.0;
            // Second permutation table at lower frequency — drives per-position
            // variation of the effective feather depth so that different sections
            // of the hull receive heavier or lighter feathering each call.
            int[]  perm2        = buildPermTable(rng);
            double depthScale   = 0.015;  // ~¼ of noiseScale → longer wavelength

            Mat    dst      = src.clone();
            byte[] rowBuf   = new byte[cols * ch];
            float[] distRow = new float[cols];

            for (int r = 0; r < rows; r++) {
                dst.get(r, 0, rowBuf);
                distInside.get(r, 0, distRow);
                boolean changed = false;

                for (int c = 0; c < cols; c++) {
                    float dist = distRow[c];
                    int   base = c * ch;

                    if (dist <= 0.0f) {
                        // ── Outside the hull H ──────────────────────────────
                        // Replace with background colour (B, G, R in OpenCV byte order)
                        rowBuf[base    ] = (byte)(bgB & 0xFF);
                        rowBuf[base + 1] = (byte)(bgG & 0xFF);
                        rowBuf[base + 2] = (byte)(bgR & 0xFF);
                        changed = true;

                    } else if (dist < featherPx * 2.0) {
                        // ── Feather zone: 0 (boundary) … 1 (inner edge) ────
                        //   localFeather = per-position feather depth driven by
                        //                  low-frequency noise; makes some hull
                        //                  sections fade far into content and
                        //                  others barely touch it — position
                        //                  pattern changes every call.
                        //   t      = normalised distance ∈ [0,1]
                        //   noise  = Perlin2D(c·s+ox, r·s+oy) ∈ [−1,1]
                        //   t′     = clamp(t + k·noise) — perturbed iso-contour
                        //   blend  = smooth-step(t′)    — C¹ continuous
                        //   pixel  = bg·(1−blend) + src·blend
                        double depthNoise   = perlin2D(c * depthScale + noiseOffsetX * 0.3,
                                                       r * depthScale + noiseOffsetY * 0.3, perm2);
                        double localFeather = featherPx * Math.max(0.2, 1.0 + 0.8 * depthNoise);
                        if (dist >= localFeather) continue; // inside this section — unchanged
                        double t      = dist / localFeather;
                        double noise  = perlin2D(c * noiseScale + noiseOffsetX,
                                                 r * noiseScale + noiseOffsetY, perm);
                        double tFibre = Math.max(0.0, Math.min(1.0, t + fiberNoise * noise));
                        double blend  = smoothStep(tFibre);

                        double srcB = rowBuf[base    ] & 0xFF;
                        double srcG = rowBuf[base + 1] & 0xFF;
                        double srcR = rowBuf[base + 2] & 0xFF;

                        rowBuf[base    ] = clampByte(bgB * (1.0 - blend) + srcB * blend);
                        rowBuf[base + 1] = clampByte(bgG * (1.0 - blend) + srcG * blend);
                        rowBuf[base + 2] = clampByte(bgR * (1.0 - blend) + srcR * blend);
                        changed = true;
                    }
                    // dist ≥ featherPx → fully inside H → original pixel unchanged
                }
                if (changed) dst.put(r, 0, rowBuf);
            }
            return dst;
        })
    );

    // -----------------------------------------------------------------------
    // Smooth-step — C¹ continuous at t=0 and t=1.
    // f(t) = t²(3 − 2t).  Maps [0,1] → [0,1] with zero derivative at both ends.
    // Prevents abrupt luminance jumps at the feather zone boundaries.
    // -----------------------------------------------------------------------
    private static double smoothStep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    // -----------------------------------------------------------------------
    // Ken Perlin's Improved 2-D Noise (2002 reference implementation).
    // Returns a value in approximately [−1, 1].
    // Used for organic paper-fibre texture in the feather zone.
    // -----------------------------------------------------------------------
    private static double perlin2D(double x, double y, int[] p) {
        int    xi = ((int) Math.floor(x)) & 255;
        int    yi = ((int) Math.floor(y)) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double u  = fade(xf);
        double v  = fade(yf);
        int aa = p[p[xi    ] + yi    ];
        int ab = p[p[xi    ] + yi + 1];
        int ba = p[p[xi + 1] + yi    ];
        int bb = p[p[xi + 1] + yi + 1];
        return lerp(v,
                lerp(u, grad2(aa, xf,     yf    ), grad2(ba, xf - 1, yf    )),
                lerp(u, grad2(ab, xf,     yf - 1), grad2(bb, xf - 1, yf - 1)));
    }

    private static double fade(double t)                     { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double grad2(int hash, double x, double y) {
        switch (hash & 3) {
            case 0: return  x + y;
            case 1: return -x + y;
            case 2: return  x - y;
            default: return -x - y;
        }
    }

    // -----------------------------------------------------------------------
    // Builds a 512-entry doubled permutation table (Fisher-Yates shuffle).
    // Doubling eliminates index wrap-around in the noise lookup.
    // -----------------------------------------------------------------------
    private static int[] buildPermTable(Random rng) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        int[] perm = new int[512];
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        return perm;
    }

    private static byte clampByte(double v) {
        return (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
    }

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (s, p) -> s)
                                .apply(input, params);
    }
}
