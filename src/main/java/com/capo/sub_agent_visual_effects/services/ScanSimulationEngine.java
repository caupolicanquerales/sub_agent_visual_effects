package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class ScanSimulationEngine {

    // -----------------------------------------------------------------------
    // Scan Simulation Engine — Homography-Based Tilt with Scan Realism
    //
    // Simulates documents captured at an angle (scanner bed tilt or off-axis
    // camera photograph).  All geometric distortions are expressed through the
    // full projective Homography model — a 3×3 matrix H that maps every source
    // pixel (x, y) to a destination pixel (x', y') via:
    //
    //   ⎡x'⎤   ⎡h₁₁ h₁₂ h₁₃⎤ ⎡x⎤
    //   ⎢y'⎥ = ⎢h₂₁ h₂₂ h₂₃⎥ ⎢y⎥
    //   ⎣w'⎦   ⎣h₃₁ h₃₂  1 ⎦ ⎣1⎦
    //
    //   x_final = x'/w',   y_final = y'/w'
    //
    // The bottom-row terms h₃₁, h₃₂ are the projective (perspective-division)
    // terms that no 2×3 affine matrix can replicate.  For a pure in-plane
    // rotation they are set to zero; for the tilt simulation they are given
    // small values proportional to sin θ / cos θ in the tilt direction so that
    // one edge of the document subtly converges toward a vanishing point.
    //
    // Scan Realism — two post-warp refinement passes
    // ─────────────────────────────────────────────────────────────────────────
    //
    // 1. Projective Shadowing
    //    Physical scanners illuminate paper from one direction; a tilted sheet
    //    receives less light on the far side.  Modelled as a 2-D linear
    //    brightness gradient in the perpendicular-to-tilt direction:
    //
    //      d       = (−sin θ,  cos θ)          — gradient direction vector
    //      t(x,y)  = dot((x−cx, y−cy), d) / R  — normalised coordinate ∈ [−1,1]
    //      B(x,y)  = 1 − α · clamp((t+1)/2, 0, 1)
    //      I'(x,y) = I(x,y) · B(x,y)          — α = shadowStrength
    //
    //    Result: the trailing edge of the tilted document is darkened by up to α.
    //
    // 2. Border Noise
    //    Regions not covered by the warped source (scanner-bed exposure) are
    //    filled with Gaussian noise instead of a hard constant colour:
    //
    //      empty mask M(x,y)  derived by warping a white canvas with same H
    //      N(x,y) ~ N(μ = borderGray, σ = noiseStd)   clamped to [0, 255]
    //      I'(x,y) = N(x,y)  where  M(x,y) = 0
    //
    // Operations
    // ────────────────────────────────────────────────────────────────────────
    //   scanrotate  — forward simulation: apply a chosen tilt angle to a clean doc
    //   scandeskew  — auto-detect existing skew and correct it; still adds border realism
    // -----------------------------------------------------------------------

    public Mat applyOperation(String name, Mat src, Map<String, Object> params) {
        return switch (name.toLowerCase()) {
            case "scanrotate" -> scanRotate(src, params);
            case "scandeskew" -> scanDeskew(src, params);
            case "scanjitter" -> scanJitter(src, params);
            default           -> src.clone();
        };
    }

    // -----------------------------------------------------------------------
    // scanrotate — forward tilt simulation
    //   params:
    //     angle          (double degrees CCW, 15.0)  — fixed in-plane rotation
    //     angleMin       (double degrees,     NaN)   — if provided together with
    //     angleMax       (double degrees,     NaN)     angleMax, a random angle
    //                                                   is sampled from [angleMin,
    //                                                   angleMax] on each call;
    //                                                   overrides 'angle'
    //     tiltStrength   (double 0–1,         0.3)   — out-of-plane perspective
    //     shadowStrength (double 0–1,         0.4)   — shadow gradient intensity
    //     noiseStd       (double 0–255,       30.0)  — border noise std-dev
    //     borderGray     (int   0–255,        20)    — border noise mean gray
    // -----------------------------------------------------------------------
    private Mat scanRotate(Mat src, Map<String, Object> params) {
        double angleMin       = getDouble(params, "angleMin",       Double.NaN);
        double angleMax       = getDouble(params, "angleMax",       Double.NaN);
        double angle;
        if (!Double.isNaN(angleMin) && !Double.isNaN(angleMax)) {
            angle = angleMin + new Random().nextDouble() * (angleMax - angleMin);
        } else {
            angle = getDouble(params, "angle", 15.0);
        }
        double tiltStrength   = getDouble(params, "tiltStrength",    0.3);
        double shadowStrength = getDouble(params, "shadowStrength",  0.4);
        double noiseStd       = getDouble(params, "noiseStd",       30.0);
        int    borderGray     = getInt   (params, "borderGray",       20);

        Mat H = buildRotationHomography(src, angle, tiltStrength);
        return applyHomographyWithEffects(src, H, angle, shadowStrength, noiseStd, borderGray);
    }

    // -----------------------------------------------------------------------
    // scandeskew — auto-detect skew and correct via homography
    //   params:
    //     maxAngle       (double, 45.0)  — safety cap for detected angle
    //     shadowStrength (double, 0.25)  — lighter shadow on corrected image
    //     noiseStd       (double, 25.0)
    //     borderGray     (int,    20)
    // -----------------------------------------------------------------------
    private Mat scanDeskew(Mat src, Map<String, Object> params) {
        double maxAngle       = getDouble(params, "maxAngle",       45.0);
        double shadowStrength = getDouble(params, "shadowStrength",  0.25);
        double noiseStd       = getDouble(params, "noiseStd",       25.0);
        int    borderGray     = getInt   (params, "borderGray",       20);

        double detectedAngle = detectSkewAngle(src, maxAngle);
        // Negate to correct; no extra perspective for a deskew operation
        Mat H = buildRotationHomography(src, -detectedAngle, 0.0);
        return applyHomographyWithEffects(src, H, -detectedAngle, shadowStrength, noiseStd, borderGray);
    }

    // -----------------------------------------------------------------------
    // scanjitter — randomised jitter for OCR training-data generation
    //
    //   Combines three independent geometric distortions sampled from uniform
    //   distributions on every call, matching the observed variation in real
    //   office and mobile-phone scan captures:
    //
    //   (A) 2D Rotation  — uniform in [−rotationMaxDeg, +rotationMaxDeg]
    //       Simulates a document placed crookedly on the scanner glass.
    //       Even ±1°–5° is critical for deskew testing.
    //
    //   (B) Keystone (Perspective) — separate pitch and yaw components
    //       Pitch (h₃₂) — top/bottom convergence; simulates viewing from above.
    //       Yaw   (h₃₁) — left/right convergence; simulates side-angle capture.
    //       Each sampled from [−max, +max] independently.
    //
    //   (C) Translation — uniform in [−translateMaxFrac·W, +translateMaxFrac·W]
    //       and [−translateMaxFrac·H, +translateMaxFrac·H].
    //       Simulates the document not being centred on the bed.
    //
    //   (D) Cylindrical Warp — sinusoidal X displacement of amplitude
    //       [0, cylinderMaxAmp·cols] simulating a page folded along a
    //       horizontal crease or part of a book spine.  Applied after
    //       the homography pipeline.
    //
    //   All ten post-warp realism passes still run automatically.
    //
    //   params:
    //     seed              (int,    -1)     — -1=truly random, ≥0=reproducible
    //     rotationMaxDeg    (double, 10.0)   — max |rotation| in degrees
    //     pitchMaxStrength  (double, 0.25)   — max pitch (top/bottom keystone)
    //     yawMaxStrength    (double, 0.15)   — max yaw   (left/right keystone)
    //     translateMaxFrac  (double, 0.05)   — max translation as fraction of dimension
    //     cylinderMaxAmp    (double, 0.02)   — max fold amplitude as fraction of cols
    //     shadowStrength    (double, 0.4)    — shadow gradient intensity
    //     noiseStd          (double, 30.0)   — border Gaussian noise std-dev
    //     borderGray        (int,    20)     — border noise mean grey level
    // -----------------------------------------------------------------------
    private Mat scanJitter(Mat src, Map<String, Object> params) {
        int    seed              = getInt   (params, "seed",              -1);
        double rotationMaxDeg    = getDouble(params, "rotationMaxDeg",   10.0);
        double pitchMaxStrength  = getDouble(params, "pitchMaxStrength",  0.25);
        double yawMaxStrength    = getDouble(params, "yawMaxStrength",    0.15);
        double translateMaxFrac  = getDouble(params, "translateMaxFrac",  0.05);
        double cylinderMaxAmp    = getDouble(params, "cylinderMaxAmp",    0.02);
        double shadowStrength    = getDouble(params, "shadowStrength",    0.4);
        double noiseStd          = getDouble(params, "noiseStd",         30.0);
        int    borderGray        = getInt   (params, "borderGray",         20);

        Random rng = seed < 0 ? new Random() : new Random((long) seed);

        // (A) 2D Rotation: uniform ±rotationMaxDeg
        double angle = (rng.nextDouble() * 2.0 - 1.0) * rotationMaxDeg;

        // (B) Keystone: independent pitch and yaw, each with random sign
        double pitchStrength = (rng.nextDouble() * 2.0 - 1.0) * pitchMaxStrength;
        double yawStrength   = (rng.nextDouble() * 2.0 - 1.0) * yawMaxStrength;

        // (C) Translation: ±translateMaxFrac of image dimensions
        double tx = (rng.nextDouble() * 2.0 - 1.0) * translateMaxFrac * src.cols();
        double ty = (rng.nextDouble() * 2.0 - 1.0) * translateMaxFrac * src.rows();

        // (D) Cylindrical fold amplitude: [0, cylinderMaxAmp * cols]
        double cylinderAmpPx = rng.nextDouble() * cylinderMaxAmp * src.cols();
        boolean cylinderConvex = rng.nextBoolean();

        Mat H = buildFullHomography(src, angle, pitchStrength, yawStrength, tx, ty);
        Mat warped = applyHomographyWithEffects(src, H, angle, shadowStrength, noiseStd, borderGray);
        H.release();

        // Apply cylindrical warp only when amplitude is perceptually meaningful
        if (cylinderAmpPx > 0.5) {
            Mat folded = applyCylindricalWarp(warped, cylinderAmpPx, cylinderConvex);
            warped.release();
            return folded;
        }
        return warped;
    }

    // -----------------------------------------------------------------------
    // Core pipeline: warp → bleed-through → shadow → border noise →
    //                salt-and-pepper bg → paper gradient → motion blur →
    //                sensor noise → ink bleed → JPEG compression → edge fuzz
    //
    // 1.  Bleed-Through   (pass A) — mirrored 6%-opacity ghost of back-page text
    // 2.  Projective Shadowing     — linear brightness gradient ⊥ tilt axis
    // 3.  Border Noise Fill        — Gaussian-noise scanner-bed fill
    // 4.  Salt-and-Pepper on Bg    — 0.4% sparse dust specks on background
    // 5.  Paper Brightness Gradient — top→bottom lamp-sweep dimming (1.0→0.92)
    // 6.  Motion Blur     (pass B) — 3-row vertical convolution (feeder jitter)
    // 7.  Sensor Noise    (pass C) — zero-mean Gaussian σ=3.5 (CMOS/CCD noise)
    // 8.  Ink Bleed                — 3×3 cross dilation of dark ink pixels
    // 9.  JPEG Compression (pass D) — quality-75 encode→decode; 8×8 DCT artifacts
    // 10. Edge Fuzz                — 1-px paper boundary Gaussian feathering
    // -----------------------------------------------------------------------
    private Mat applyHomographyWithEffects(Mat src, Mat H,
                                           double angle,
                                           double shadowStrength,
                                           double noiseStd,
                                           int    borderGray) {
        Size outSize = src.size();

        // ── Step 1: Full projective warp (empty regions filled with constant 0) ──
        //    INTER_CUBIC avoids the aliasing staircase artifacts that INTER_LINEAR
        //    produces on thin text strokes when resampled at non-axis-aligned angles.
        Mat warped = new Mat();
        Imgproc.warpPerspective(src, warped, H, outSize,
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar.all(0));

        // ── Step 2: Detect empty border regions via auxiliary mask ──────────────
        Mat whiteMask = new Mat(src.size(), CvType.CV_8UC1, Scalar.all(255));
        Mat warpedMask = new Mat();
        Imgproc.warpPerspective(whiteMask, warpedMask, H, outSize,
                Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar.all(0));
        whiteMask.release();

        // borderMask == 255 → pixel is in the empty scanner-bed region
        // paperMask  == 255 → pixel belongs to the warped document
        Mat borderMask = new Mat();
        Core.compare(warpedMask, Scalar.all(0), borderMask, Core.CMP_EQ);
        Mat paperMask = new Mat();
        Core.compare(warpedMask, Scalar.all(0), paperMask, Core.CMP_GT);
        warpedMask.release();

        // ── Step 3: Bleed-through — ghost image of back-page text ────────────────
        Mat withBleedThrough = applyBleedThrough(warped, paperMask);
        warped.release();

        // ── Step 4: Projective shadowing ────────────────────────────────────────
        Mat shadowed = applyProjectiveShadow(withBleedThrough, angle, shadowStrength);
        withBleedThrough.release();

        // ── Step 5: Border noise fill ────────────────────────────────────────────
        Mat withBorderNoise = applyBorderNoise(shadowed, borderMask, borderGray, noiseStd);
        shadowed.release();

        // ── Step 6: Salt-and-Pepper noise on background ──────────────────────────
        Mat withSaltPepper = applySaltAndPepperBackground(withBorderNoise, borderMask);
        withBorderNoise.release();
        borderMask.release();

        // ── Step 7: Paper brightness gradient ────────────────────────────────────
        Mat withGradient = applyPaperBrightnessGradient(withSaltPepper, paperMask);
        withSaltPepper.release();

        // ── Step 8: Motion blur — vertical scanner-sweep jitter ──────────────────
        Mat withMotionBlur = applyMotionBlur(withGradient, paperMask);
        withGradient.release();

        // ── Step 9: Sensor noise — CMOS/CCD Gaussian noise on paper ──────────────
        Mat withSensorNoise = applySensorNoise(withMotionBlur, paperMask);
        withMotionBlur.release();

        // ── Step 10: Ink bleed ────────────────────────────────────────────────────
        Mat withInkBleed = applyInkBleed(withSensorNoise, paperMask);
        withSensorNoise.release();

        // ── Step 11: JPEG compression artifacts — quality-75 8×8 DCT blocking ────
        Mat withJpeg = applyJpegCompression(withInkBleed);
        withInkBleed.release();

        // ── Step 12: Edge fuzz — soft composite at paper boundary ────────────────
        Mat result = applyEdgeFuzz(withJpeg, paperMask);
        withJpeg.release();
        paperMask.release();

        return result;
    }

    // -----------------------------------------------------------------------
    // Edge Fuzz
    //   Produces a 1–2 px soft feather at the paper boundary so the edge
    //   transitions naturally into the background instead of being a
    //   mathematically perfect razor-sharp line.
    //
    //   Algorithm:
    //     1. Erode paperMask by 1 px (3×3 rect) → erodedMask
    //     2. fringeMask = paperMask − erodedMask  (exactly the 1-px boundary ring)
    //     3. Gaussian-blur the full image with a 3×3 kernel
    //        → at fringe pixels this averages the paper-edge colour with the
    //          immediately adjacent background (border-noise) pixels
    //     4. Copy blurred pixels into result ONLY at fringeMask pixels
    //        → interior paper untouched, background untouched, only the
    //          boundary ring is softened
    // -----------------------------------------------------------------------
    private Mat applyEdgeFuzz(Mat src, Mat paperMask) {
        // Step 1: erode by 1 px to find the strict interior
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat erodedMask = new Mat();
        Imgproc.erode(paperMask, erodedMask, kernel);

        // Step 2: fringe = original mask minus eroded mask (1-px boundary ring)
        Mat fringeMask = new Mat();
        Core.subtract(paperMask, erodedMask, fringeMask);
        erodedMask.release();

        // Step 3: Gaussian blur of the whole image (3×3, sigma auto)
        //   At fringe pixels this naturally blends paper-edge colours with the
        //   adjacent background, giving an organic feathered transition.
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new Size(3, 3), 0);

        // Step 4: stamp blurred fringe pixels onto a clone of src
        Mat result = src.clone();
        blurred.copyTo(result, fringeMask);
        blurred.release();
        fringeMask.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Cylindrical Warp
    //   Simulates a page that was folded in an envelope, rolled slightly, or
    //   is part of a thick book spine where the page curves away from the
    //   scanner bed.  Modelled as a sinusoidal X-axis displacement:
    //
    //     x_src(x, y)  =  x  +  ampPx · sin(π · y / rows)
    //     y_src(x, y)  =  y
    //
    //   The sine peaks at the mid-height of the page (maximum horizontal spread
    //   there) and returns to zero at both the top and bottom edges, matching
    //   the physical shape of a page curved over a cylindrical axis.
    //   `convex` controls the warp direction (toward vs away from the scan bar).
    //   BORDER_REPLICATE avoids hard black margins on either side.
    //
    //   Applied AFTER the homography pipeline (separate from the paper mask)
    //   since the entire frame warps as one piece.
    // -----------------------------------------------------------------------
    private Mat applyCylindricalWarp(Mat src, double ampPx, boolean convex) {
        int rows = src.rows();
        int cols = src.cols();
        double sign = convex ? 1.0 : -1.0;

        Mat mapX = new Mat(rows, cols, CvType.CV_32F);
        Mat mapY = new Mat(rows, cols, CvType.CV_32F);

        float[] rowX = new float[cols];
        float[] rowY = new float[cols];

        for (int y = 0; y < rows; y++) {
            float xOffset = (float) (sign * ampPx * Math.sin(Math.PI * y / rows));
            for (int x = 0; x < cols; x++) {
                rowX[x] = x + xOffset;
                rowY[x] = y;
            }
            mapX.put(y, 0, rowX);
            mapY.put(y, 0, rowY);
        }

        Mat result = new Mat();
        Imgproc.remap(src, result, mapX, mapY, Imgproc.INTER_CUBIC,
                      Core.BORDER_REPLICATE);
        mapX.release();
        mapY.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Bleed-Through
    //   Simulates thin paper where text printed on the reverse side shows
    //   faintly through the sheet.  Modelled by horizontally mirroring the
    //   already-warped document and blending it onto the paper region at a
    //   low fixed opacity:
    //
    //     ghost(x, y) = flipH( warped(x, y) )
    //     result      = (1 − α) · src  +  α · ghost      α = 0.06
    //
    //   The horizontal flip places each character at the position it would
    //   occupy when the page is turned over (left-right reversed).  Applied
    //   only inside paperMask so the scanner-bed background is unaffected.
    // -----------------------------------------------------------------------
    private Mat applyBleedThrough(Mat src, Mat paperMask) {
        Mat ghost = new Mat();
        Core.flip(src, ghost, 1);   // flipCode 1 = horizontal mirror

        // Blur heavily so individual characters are not recognisable —
        // real bleed-through is diffuse ink haze, never a sharp mirror.
        int ksize = 51; // must be odd; larger = more diffuse
        Imgproc.GaussianBlur(ghost, ghost, new Size(ksize, ksize), 0);

        Mat srcF = new Mat(), ghostF = new Mat();
        src.convertTo(srcF, CvType.CV_32F);
        ghost.convertTo(ghostF, CvType.CV_32F);
        ghost.release();

        Core.addWeighted(srcF, 1.0 - BLEED_THROUGH_OPACITY,
                         ghostF, BLEED_THROUGH_OPACITY, 0.0, srcF);
        ghostF.release();

        Mat modified = new Mat();
        srcF.convertTo(modified, src.type());
        srcF.release();

        Mat result = src.clone();
        modified.copyTo(result, paperMask);
        modified.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Motion Blur
    //   Simulates the directional smear from paper movement across the sensor
    //   line (document feeder) or from the scan-bar sweep (flatbed).  Both
    //   cases produce a slight vertical smear.  A 3-row × 1-col uniform
    //   averaging kernel introduces ≈1.5 px of vertical blur.
    //
    //     kernel = [1/3, 1/3, 1/3]ᵀ   (column vector, 3 rows)
    //     I'(x, y) = I ⊛ kernel        via filter2D with BORDER_REPLICATE
    //
    //   Applied only to paper pixels; the scanner-bed border is unaffected.
    // -----------------------------------------------------------------------
    private Mat applyMotionBlur(Mat src, Mat paperMask) {
        Mat kernel = new Mat(MOTION_BLUR_SIZE, 1, CvType.CV_32F,
                             Scalar.all(1.0 / MOTION_BLUR_SIZE));
        Mat blurred = new Mat();
        Imgproc.filter2D(src, blurred, -1, kernel,
                         new Point(-1, -1), 0.0, Core.BORDER_REPLICATE);
        kernel.release();

        Mat result = src.clone();
        blurred.copyTo(result, paperMask);
        blurred.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Sensor Noise
    //   Adds zero-mean Gaussian noise to paper pixels, simulating the thermal
    //   and photon-shot noise produced by CMOS/CCD scanner arrays.
    //
    //     N(x, y) ~ N(0, σ²)      σ = SENSOR_NOISE_STD = 3.5
    //     I'(x, y) = clamp(I(x,y) + N(x,y), 0, 255)
    //
    //   Independent per-channel noise prevents artificial colour casts.
    //   Applied only to paper pixels; background noise is handled separately
    //   by applyBorderNoise and applySaltAndPepperBackground.
    // -----------------------------------------------------------------------
    private Mat applySensorNoise(Mat src, Mat paperMask) {
        int rows = src.rows();
        int cols = src.cols();
        int ch   = src.channels();

        int noiseType = ch == 1 ? CvType.CV_32FC1 : CvType.CV_32FC3;
        Mat noiseF = new Mat(rows, cols, noiseType);
        Core.randn(noiseF, 0.0, SENSOR_NOISE_STD);

        Mat srcF = new Mat();
        src.convertTo(srcF, CvType.CV_32F);
        Core.add(srcF, noiseF, srcF);
        noiseF.release();

        Imgproc.threshold(srcF, srcF, 255.0, 255.0, Imgproc.THRESH_TRUNC);
        Imgproc.threshold(srcF, srcF, 0.0,   0.0,   Imgproc.THRESH_TOZERO);

        Mat modified = new Mat();
        srcF.convertTo(modified, src.type());
        srcF.release();

        Mat result = src.clone();
        modified.copyTo(result, paperMask);
        modified.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // JPEG Compression
    //   Most office scanners save to JPEG or PDF/JPEG with medium compression.
    //   This pass performs a round-trip encode → decode cycle at quality 75 to
    //   introduce authentic 8×8 DCT quantisation blocking around the high-
    //   contrast edges of letter strokes — the most challenging artefact for
    //   OCR engines to handle correctly.
    //
    //   Applied to the full image (paper + border) since a real scanner saves
    //   the complete frame.  The encode/decode preserves channel count.
    // -----------------------------------------------------------------------
    private Mat applyJpegCompression(Mat src) {
        MatOfByte buf    = new MatOfByte();
        MatOfInt  params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
        boolean   ok     = Imgcodecs.imencode(".jpg", src, buf, params);
        params.release();

        if (!ok || buf.empty()) {
            buf.release();
            return src.clone();
        }

        Mat decoded = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_UNCHANGED);
        buf.release();

        if (decoded.empty()) {
            decoded.release();
            return src.clone();
        }
        return decoded;
    }

    // -----------------------------------------------------------------------
    // Paper Brightness Gradient
    //   Simulates the scanner lamp sweep by darkening the document from top
    //   to bottom (configurable direction).  Only paper pixels are modified.
    //
    //     g(y) = GRADIENT_DARK + (1 − GRADIENT_DARK) · (1 − y / rows)
    //     I'   = I · g   for pixels inside paperMask
    //
    //   GRADIENT_DARK = 0.95 → paper top is full white, bottom is ~5% dimmer.
    // -----------------------------------------------------------------------
    private static final double GRADIENT_DARK = 0.92;

    private Mat applyPaperBrightnessGradient(Mat src, Mat paperMask) {
        int rows = src.rows();
        int cols = src.cols();
        int ch   = src.channels();

        // Build row-wise gradient map: shape (rows, cols), float
        Mat gradMap = new Mat(rows, cols, CvType.CV_32F);
        for (int y = 0; y < rows; y++) {
            float gVal = (float) (GRADIENT_DARK + (1.0 - GRADIENT_DARK) * (1.0 - (double) y / rows));
            gradMap.row(y).setTo(new Scalar(gVal));
        }

        Mat srcF = new Mat();
        src.convertTo(srcF, CvType.CV_32F);

        if (ch > 1) {
            List<Mat> planes = new ArrayList<>();
            Core.split(srcF, planes);
            for (int c = 0; c < ch; c++) {
                Core.multiply(planes.get(c), gradMap, planes.get(c));
            }
            Core.merge(planes, srcF);
            for (Mat p : planes) p.release();
        } else {
            Core.multiply(srcF, gradMap, srcF);
        }
        gradMap.release();

        Mat modifiedFull = new Mat();
        srcF.convertTo(modifiedFull, src.type());
        srcF.release();

        // Blend: apply gradient only to paper region, preserve background
        Mat result = src.clone();
        modifiedFull.copyTo(result, paperMask);
        modifiedFull.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Salt-and-Pepper Noise on Background
    //   Adds sparse bright (salt) and dark (pepper) pixels to the scanner-bed
    //   background area, simulating dust specs and sensor noise.
    //
    //   Default densities: salt = 0.004, pepper = 0.004  (≈ 0.4% of pixels each)
    // -----------------------------------------------------------------------
    private static final double SP_SALT_PROB   = 0.004;
    private static final double SP_PEPPER_PROB = 0.004;

    // ── New realism-pass constants — no caller params, all run automatically ──────
    private static final double BLEED_THROUGH_OPACITY = 0.06;  // back-page ghost at 6% opacity
    private static final int    MOTION_BLUR_SIZE       = 3;    // 3-row vertical kernel (≈1.5 px)
    private static final double SENSOR_NOISE_STD       = 3.5;  // CCD/CMOS Gaussian σ per channel
    private static final int    JPEG_QUALITY           = 75;   // medium office-scanner JPEG quality

    private Mat applySaltAndPepperBackground(Mat src, Mat borderMask) {
        if (Core.countNonZero(borderMask) == 0) return src.clone();

        int rows = src.rows();
        int cols = src.cols();

        // Generate uniform random field [0,1)
        Mat randField = new Mat(rows, cols, CvType.CV_32F);
        Core.randu(randField, 0.0, 1.0);

        // Salt mask: rand < SP_SALT_PROB  AND  in border
        Mat saltMask = new Mat(), pepperMask = new Mat();
        Core.compare(randField, Scalar.all(SP_SALT_PROB), saltMask, Core.CMP_LT);
        Core.compare(randField, Scalar.all(1.0 - SP_PEPPER_PROB), pepperMask, Core.CMP_GT);
        randField.release();

        // Intersect with borderMask
        Core.bitwise_and(saltMask, borderMask, saltMask);
        Core.bitwise_and(pepperMask, borderMask, pepperMask);

        Mat result = src.clone();
        result.setTo(Scalar.all(255), saltMask);
        result.setTo(Scalar.all(0),   pepperMask);
        saltMask.release(); pepperMask.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Ink Bleed
    //   Simulates ink absorbed into paper fibres as a slight stroke expansion.
    //
    //   Only a 3×3 cross-shaped dilation is applied — this expands dark (ink)
    //   pixels by exactly 1 pixel in the cardinal directions, mimicking the tiny
    //   spread of ink into adjacent paper fibres.
    //
    //   The earlier median-blur step is intentionally omitted: medianBlur on text
    //   rounds off character corners and creates compression-artifact-like
    //   staircase blocking that corrupts OCR training data.  A cross kernel (not
    //   rect) avoids filling diagonal gaps so that closely-spaced glyphs do not
    //   merge into each other.
    //
    //   Applied only within the paper region (paperMask > 0).
    // -----------------------------------------------------------------------
    private Mat applyInkBleed(Mat src, Mat paperMask) {
        // Cross-shaped kernel: expands dark pixels in 4 cardinal directions only
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, new Size(3, 3));
        Mat dilated = new Mat();
        Imgproc.dilate(src, dilated, kernel);

        // Merge back: apply ink-bleed only where paperMask is set
        Mat result = src.clone();
        dilated.copyTo(result, paperMask);
        dilated.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Build the 3×3 rotation homography around the image centre.
    //
    // Rotation component (identical to the affine part of getRotationMatrix2D,
    // lifted to 3×3 by appending the perspective row):
    //
    //   h₁₁ =  cos θ,  h₁₂ = −sin θ,  h₁₃ = cx(1−cos θ) + cy·sin θ
    //   h₂₁ =  sin θ,  h₂₂ =  cos θ,  h₂₃ = cy(1−cos θ) − cx·sin θ
    //
    // Perspective tilt — small projective terms that produce a subtle keystone
    // distortion simulating out-of-plane tilt in the direction of the rotation:
    //
    //   h₃₁ = tiltStrength · sin θ / dim
    //   h₃₂ = tiltStrength · cos θ / dim     (dim = max(cols, rows))
    //   h₃₃ = 1
    //
    // Corner displacement from perspective:  Δ ≈ tiltStrength · 0.5 pixels at
    // the farthest corner for small tiltStrength — subtle but physically present.
    // -----------------------------------------------------------------------
    private Mat buildRotationHomography(Mat src, double angleDeg, double tiltStrength) {
        double theta = Math.toRadians(angleDeg);
        double sinT  = Math.sin(theta);
        double cosT  = Math.cos(theta);
        double dim   = Math.max(src.cols(), src.rows());
        // Express the coupled tilt as independent pitch/yaw normalized to image dims
        double pitchStrength = tiltStrength * cosT * src.rows() / dim;
        double yawStrength   = tiltStrength * sinT * src.cols() / dim;
        return buildFullHomography(src, angleDeg, pitchStrength, yawStrength, 0.0, 0.0);
    }

    // -----------------------------------------------------------------------
    // Build the full 3×3 homography combining rotation, independent keystone
    // pitch/yaw, and translation — used by scanjitter.
    //
    // Parameters (all relative to image centre as origin):
    //   angleDeg      — in-plane CCW rotation in degrees
    //   pitchStrength — keystone strength for top/bottom convergence.
    //                   Maps to h₃₂ = pitchStrength / rows.
    //                   Positive → top edge wider than bottom ("looking down").
    //   yawStrength   — keystone strength for left/right convergence.
    //                   Maps to h₃₁ = yawStrength / cols.
    //                   Positive → right edge wider than left ("looking left").
    //   tx / ty       — pixel translation after rotation (shifts the result frame)
    //
    // Full matrix:
    //   ⎡ cos θ   −sin θ   cx(1−cos θ)+cy·sin θ + tx ⎤
    //   ⎢ sin θ    cos θ   cy(1−cos θ)−cx·sin θ + ty ⎥
    //   ⎣ yaw/W   pitch/H          1                  ⎦
    // -----------------------------------------------------------------------
    private Mat buildFullHomography(Mat src, double angleDeg,
                                    double pitchStrength, double yawStrength,
                                    double tx, double ty) {
        double theta = Math.toRadians(angleDeg);
        double cosT  = Math.cos(theta);
        double sinT  = Math.sin(theta);
        double cx    = src.cols() / 2.0;
        double cy    = src.rows() / 2.0;
        double W     = src.cols();
        double H     = src.rows();

        double h11 = cosT;
        double h12 = -sinT;
        double h13 = cx * (1.0 - cosT) + cy * sinT + tx;
        double h21 = sinT;
        double h22 = cosT;
        double h23 = cy * (1.0 - cosT) - cx * sinT + ty;
        double h31 = yawStrength   / W;   // left/right keystone
        double h32 = pitchStrength / H;   // top/bottom keystone

        Mat Hmat = new Mat(3, 3, CvType.CV_64F);
        Hmat.put(0, 0,
                 h11, h12, h13,
                 h21, h22, h23,
                 h31, h32, 1.0);
        return Hmat;
    }

    // -----------------------------------------------------------------------
    // Projective Shadowing
    //
    //   Gradient direction: d = (−sin θ, cos θ)  (perpendicular to tilt axis)
    //
    //   For each pixel (x, y):
    //     t(x,y) = [ (x−cx)·(−sin θ) + (y−cy)·cos θ ] / R   ∈ [−1, 1] approx.
    //     B(x,y) = 1 − α · clamp((t+1)/2, 0, 1)             α = shadowStrength
    //     I'    = I · B   (per channel)
    //
    //   The gradient is built efficiently using two 1-D vectors (O(W+H)) that
    //   are tiled to full size by Core.repeat before the element-wise add.
    // -----------------------------------------------------------------------
    private Mat applyProjectiveShadow(Mat src, double angleDeg, double shadowStrength) {
        if (shadowStrength <= 0.0) return src.clone();

        double theta = Math.toRadians(angleDeg);
        double dx    = -Math.sin(theta);   // gradient x-component
        double dy    =  Math.cos(theta);   // gradient y-component
        int    cols  = src.cols();
        int    rows  = src.rows();
        double cx    = cols / 2.0;
        double cy    = rows / 2.0;
        double R     = Math.hypot(cols, rows) / 2.0;

        // 1-D horizontal contribution: shape (1, cols)
        Mat rowGrad = new Mat(1, cols, CvType.CV_32F);
        for (int x = 0; x < cols; x++) {
            rowGrad.put(0, x, (float) ((x - cx) * dx / R));
        }
        // 1-D vertical contribution: shape (rows, 1)
        Mat colGrad = new Mat(rows, 1, CvType.CV_32F);
        for (int y = 0; y < rows; y++) {
            colGrad.put(y, 0, (float) ((y - cy) * dy / R));
        }

        // Expand both to (rows, cols) and sum → t(x,y)
        Mat rowFull = new Mat(), colFull = new Mat();
        Core.repeat(rowGrad, rows, 1, rowFull); rowGrad.release();
        Core.repeat(colGrad, 1, cols, colFull); colGrad.release();

        Mat t = new Mat();
        Core.add(rowFull, colFull, t);
        rowFull.release(); colFull.release();

        // clamp((t+1)/2, 0, 1)
        Core.add(t, Scalar.all(1.0), t);
        Core.multiply(t, Scalar.all(0.5), t);
        Imgproc.threshold(t, t, 1.0, 1.0, Imgproc.THRESH_TRUNC);   // cap at 1
        Imgproc.threshold(t, t, 0.0, 0.0, Imgproc.THRESH_TOZERO);  // floor at 0

        // B = 1 − shadowStrength · t
        Core.multiply(t, Scalar.all(shadowStrength), t);
        Mat B = new Mat(rows, cols, CvType.CV_32F, Scalar.all(1.0));
        Core.subtract(B, t, B);
        t.release();

        // Multiply src (as float) by B (broadcast across channels)
        Mat srcF = new Mat();
        src.convertTo(srcF, CvType.CV_32F);

        int ch = src.channels();
        if (ch > 1) {
            List<Mat> bPlanes = new ArrayList<>();
            for (int c = 0; c < ch; c++) bPlanes.add(B.clone());
            Mat Bmc = new Mat();
            Core.merge(bPlanes, Bmc);
            for (Mat p : bPlanes) p.release();
            B.release();
            Core.multiply(srcF, Bmc, srcF);
            Bmc.release();
        } else {
            Core.multiply(srcF, B, srcF);
            B.release();
        }

        Mat result = new Mat();
        srcF.convertTo(result, src.type());
        srcF.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Border Noise Fill
    //
    //   Pixels flagged by borderMask are replaced with:
    //     N(x,y) ~ N(μ = borderGray, σ = noiseStd),  clamped to [0, 255]
    //   For multi-channel images, independent per-channel noise is generated
    //   (same μ, same σ) so the border does not appear colour-cast.
    //   Implementation uses Core.randn on a CV_32F Mat, clamps, then converts
    //   back to 8-bit, and copies into result via the mask.
    // -----------------------------------------------------------------------
    private Mat applyBorderNoise(Mat src, Mat borderMask, int borderGray, double noiseStd) {
        if (Core.countNonZero(borderMask) == 0) return src.clone();

        int rows = src.rows();
        int cols = src.cols();
        int ch   = src.channels();

        // Generate Gaussian noise in float (per-channel independent)
        int noiseType = ch == 1 ? CvType.CV_32FC1 : CvType.CV_32FC3;
        Mat noiseF = new Mat(rows, cols, noiseType);
        Core.randn(noiseF, (double) borderGray, noiseStd);

        // Clamp to [0, 255]: split for multi-channel since threshold is per-channel
        if (ch == 1) {
            Imgproc.threshold(noiseF, noiseF, 255.0, 255.0, Imgproc.THRESH_TRUNC);
            Imgproc.threshold(noiseF, noiseF, 0.0,   0.0,   Imgproc.THRESH_TOZERO);
        } else {
            List<Mat> planes = new ArrayList<>();
            Core.split(noiseF, planes);
            for (Mat plane : planes) {
                Imgproc.threshold(plane, plane, 255.0, 255.0, Imgproc.THRESH_TRUNC);
                Imgproc.threshold(plane, plane, 0.0,   0.0,   Imgproc.THRESH_TOZERO);
            }
            Core.merge(planes, noiseF);
            for (Mat p : planes) p.release();
        }

        // Convert to 8-bit and stamp onto a clone of src using the border mask
        Mat noise8 = new Mat();
        noiseF.convertTo(noise8, src.type());
        noiseF.release();

        Mat result = src.clone();
        noise8.copyTo(result, borderMask);   // only writes where mask == non-zero
        noise8.release();
        return result;
    }

    // -----------------------------------------------------------------------
    // Skew angle detection
    //   1. Binarise (Otsu, inverted) to expose foreground as white.
    //   2. Collect all foreground pixel coordinates.
    //   3. Fit a minimum-area rotated rectangle → read its angle.
    //   4. Normalise: minAreaRect returns angle ∈ (-90°, 0°]; add 90° when the
    //      box is portrait-oriented (width < height).
    // -----------------------------------------------------------------------
    private double detectSkewAngle(Mat src, double maxAngle) {
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

        Mat coords = new Mat();
        Core.findNonZero(thresh, coords);
        thresh.release();

        if (coords.empty()) { coords.release(); return 0.0; }

        MatOfPoint2f pts = new MatOfPoint2f(coords.reshape(2, coords.rows()));
        coords.release();
        RotatedRect box = Imgproc.minAreaRect(pts);
        pts.release();

        double angle = box.angle;
        if (box.size.width < box.size.height) angle += 90.0;
        return Math.abs(angle) > maxAngle ? 0.0 : angle;
    }

    // ── parameter helpers ───────────────────────────────────────────────────

    private static double getDouble(Map<String, Object> p, String key, double def) {
        Object v = p.get(key);
        return v == null ? def : ((Number) v).doubleValue();
    }

    private static int getInt(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        return v == null ? def : ((Number) v).intValue();
    }
}
