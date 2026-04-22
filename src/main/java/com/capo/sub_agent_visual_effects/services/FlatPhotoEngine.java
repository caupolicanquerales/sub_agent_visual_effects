package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class FlatPhotoEngine {

    // -----------------------------------------------------------------------
    // Flat Photo Engine
    //
    // Simulates a photographic look on a geometrically flat (non-keystoned)
    // image.  Seven optical layers are composed in sequence; each pass can be
    // individually enabled or disabled via boolean params.
    //
    // ── 0. RADIAL BARREL DISTORTION ────────────────────────────────────────
    //
    //   Real photographs rarely show a perfectly rectangular document.
    //   A slight barrel distortion breaks the "digital squareness" that
    //   reveals synthetic images.  Brown–Conrady backward-mapping polynomial:
    //
    //     X = x − cx,   Y = y − cy
    //     r² = (X² + Y²) / norm²      (norm = max(cx, cy))
    //
    //     x_src = cx + X / (1 + k1·r² + k2·r⁴)
    //     y_src = cy + Y / (1 + k1·r² + k2·r⁴)
    //
    //   k1 > 0 → barrel   (source coords pulled toward centre → content bows outward)
    //   k1 < 0 → pincushion (source coords pushed outward → content bows inward)
    //   INTER_LANCZOS4 + BORDER_REPLICATE preserve text sharpness at curved edges.
    //
    // ── 1. AFFINE TRANSFORM — PARALLEL PRESERVATION ────────────────────────
    //
    //   An Affine Transform uses a strict 2×3 matrix (implicit bottom row
    //   [0, 0, 1]), which means there is NO perspective division and therefore
    //   NO w component.  Parallel lines are preserved — the image is sheared
    //   into a parallelogram, never a trapezoid:
    //
    //     [x']   [a11 a12] [x]   [b1]
    //     [y'] = [a21 a22] [y] + [b2]
    //
    //   This simulates a telephoto lens (very large focal length) where
    //   perspective compression is negligible.
    //
    //   The matrix combines a small rotation θ (degrees) with optional
    //   horizontal shear (shearX) and vertical shear (shearY), while
    //   fixing the image centre so no translation crops occur:
    //
    //     a11 = cosθ + shearX·sinθ       a12 = −sinθ + shearX·cosθ
    //     a21 = sinθ + shearY·cosθ       a22 =  cosθ − shearY·sinθ
    //     b1  = cx − (a11·cx + a12·cy)   b2  = cy − (a21·cx + a22·cy)
    //
    // ── 2. COSINE FALLOFF — LUMINANCE GRADIENT ─────────────────────────────
    //
    //   Even without geometry distortion, realistic lighting is rarely
    //   centred.  A virtual point-light source at (lx, ly) creates a
    //   directional brightness falloff modelled by the Inverse Square Law
    //   (linearised for subtle effect):
    //
    //     d(x,y) = sqrt((x − lx)² + (y − ly)²) / Rmax     ∈ [0, 1]
    //
    //   where Rmax = max dist(light, corner) normalises d to [0, 1].
    //
    //     I'(x,y) = I(x,y) · (1 − α · d(x,y))             clamped [0, 255]
    //
    //   At α = 0.10–0.20 this produces a 10–20 % falloff at the darkest
    //   corner.  Computed via convertTo (α * src + β) + vectorised multiply
    //   — no per-pixel Java loop for the multiply step.
    //
    // ── 2b. SPECULAR HIGHLIGHT — MICRO-REFLECTION ──────────────────────────
    //
    //   Even matte paper has micro-facets that produce a localised brightness
    //   peak.  A narrow Gaussian spot at (specX, specY) simulates a specular
    //   reflection from a real light source hitting the paper fibres:
    //
    //     σ_s = specSigma · min(rows, cols)
    //     spot(x,y) = specStrength · exp(−((x−sx)² + (y−sy)²) / (2·σ_s²))
    //     I'(x,y)   = clamp(I(x,y) + 255·spot(x,y), 0, 255)
    //
    //   Applied before tilt-shift so the DoF blur naturally softens the
    //   spot toward the unfocused edge.  specStrength=0.06 gives a visible
    //   but unobtrusive ≈15 % brightness peak at the chosen corner.
    //
    // ── 3. TILT-SHIFT — Z-PLANE SPACE-VARIANT GAUSSIAN BLUR ───────────────
    //
    //   A horizontal "line of focus" at row y_focus divides the image into
    //   a sharp near-field and a progressively blurred far-field.  The σ of
    //   the Gaussian blur follows a power law:
    //
    //     σ(y) = tiltShiftK · |y − y_focus|^p
    //
    //   p = 1 → linear falloff (standard shallow DoF)
    //   p = 2 → parabolic falloff (very short depth of field)
    //
    //   Implemented as a vectorised blend (no per-row re-blur call):
    //
    //     α(y) = (|y − y_focus| / maxDist)^p     ∈ [0, 1]
    //     result = sharp · (1 − α)  +  blurred_max · α
    //
    //   The viewer's brain interprets the blur gradient as depth,
    //   perceiving the unfocused region as farther away even though the
    //   geometry is entirely flat (no trapezoid).
    //
    // ── 4. CHROMATIC ABERRATION — RADIAL LENS FRINGING ─────────────────────
    //
    //   Real lenses refract R, G, B wavelengths at slightly different angles.
    //   Each channel is remapped individually using radial backward mapping:
    //
    //     X = x − cx,   Y = y − cy
    //     r² = (X² + Y²) / norm²               (normalised ∈ [0, 1])
    //
    //     x_src = cx + X / (1 + κ · r²)
    //     y_src = cy + Y / (1 + κ · r²)
    //
    //   κ_Red > 0  → red channel magnified outward (barrel for R)
    //   κ_Blue < 0 → blue channel compressed inward (pincushion for B)
    //   G channel  → κ = 0, no displacement
    //
    //   The resulting colour fringing at the corners is a hallmark of
    //   physical optics and makes a flat CGI image read as "photographic."
    //
    // ── 5. ISO SENSOR GRAIN — ADDITIVE GAUSSIAN NOISE ──────────────────────
    //
    //   Film grain / digital sensor noise is modelled as additive zero-mean
    //   Gaussian noise applied independently per pixel:
    //
    //     I''(x,y) = clamp(I'(x,y) + N(0, σ_grain), 0, 255)
    //
    //   Independent per-channel sampling prevents artificial colour casts.
    //   Uses Core.randn on a float Mat for efficiency.
    //
    // -----------------------------------------------------------------------
    // params:
    //   affineEnabled     (boolean, true)   — step 1: apply affine warp
    //   affineAngle       (double,  0.0)    — rotation in degrees (CCW); positive = tilt left
    //   affineShearX      (double,  0.02)   — horizontal shear factor (keep ≤0.04; larger values split 1-px borders)
    //   affineShearY      (double,  0.0)    — vertical shear factor
    //   borderMode        (String, "replicate") — fill for affine border pixels
    //
    //   lightEnabled      (boolean, true)   — step 2: apply luminance gradient
    //   lightX            (double,  0.75)   — virtual light X as fraction of width  [0,1]
    //   lightY            (double,  0.25)   — virtual light Y as fraction of height [0,1]
    //   lightDecay        (double,  0.15)   — α decay constant (0=no falloff, 0.5=strong)
    //
    //   tiltShiftEnabled  (boolean, true)   — step 3: apply tilt-shift blur
    //   focusY            (double,  0.5)    — focus-line Y as fraction of height [0,1]
    //   tiltShiftK        (double,  1.8)    — blur coefficient (higher = more blur at edges)
    //   tiltShiftP        (double,  1.0)    — power (1=linear, 2=parabolic)
    //   tiltShiftMaxSigma (double, 12.0)    — σ cap in pixels at the far edge
    //
    //   chromaEnabled     (boolean, true)   — step 4: apply chromatic aberration
    //   chromaRed         (double,  0.008)  — κ for Red channel  (positive = outward; realistic ≤0.015)
    //   chromaBlue        (double, -0.005)  — κ for Blue channel (negative = inward; realistic ≥-0.010)
    //
    //   grainEnabled      (boolean, true)   — step 5: apply ISO grain noise
    //   grainSigma        (double,  4.0)    — σ for additive Gaussian grain
    //
    //   barrelEnabled     (boolean, true)   — step 0: apply barrel distortion
    //   barrelK1          (double,  0.008)  — κ1: + barrel / − pincushion (realistic ≤ 0.030)
    //   barrelK2          (double,  0.0)    — κ2: higher-order radial correction
    //
    //   specEnabled       (boolean, true)   — step 2b: apply specular highlight
    //   specX             (double,  0.15)   — spot centre X as fraction of width  [0, 1]
    //   specY             (double,  0.12)   — spot centre Y as fraction of height [0, 1]
    //   specStrength      (double,  0.06)   — peak brightness boost (0 = none, 0.20 = strong)
    //   specSigma         (double,  0.12)   — spot radius as fraction of min(W, H)
    // -----------------------------------------------------------------------

    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry =
        Map.of("flatphoto", (src, params) -> {

            boolean barrelEnabled    = boolParam(params, "barrelEnabled",    true);
            double  barrelK1         = dblParam (params, "barrelK1",          0.008);
            double  barrelK2         = dblParam (params, "barrelK2",          0.0);

            boolean specEnabled      = boolParam(params, "specEnabled",      true);
            double  specX            = dblParam (params, "specX",             0.15);
            double  specY            = dblParam (params, "specY",             0.12);
            double  specStrength     = dblParam (params, "specStrength",      0.06);
            double  specSigma        = dblParam (params, "specSigma",         0.12);

            boolean affineEnabled    = boolParam(params, "affineEnabled",    true);
            double  affineAngle      = dblParam (params, "affineAngle",       0.0);
            double  affineShearX     = dblParam (params, "affineShearX",      0.02);
            double  affineShearY     = dblParam (params, "affineShearY",      0.0);
            String  borderStr        = strParam (params, "borderMode",        "replicate");

            boolean lightEnabled     = boolParam(params, "lightEnabled",     true);
            // Default: centre-symmetric vignette (cos⁴θ model) — lightX/Y=0.5 means the
            // bright spot is at the image centre; corners fall off evenly by lightDecay.
            double  lightX           = dblParam (params, "lightX",            0.5);
            double  lightY           = dblParam (params, "lightY",            0.5);
            double  lightDecay       = dblParam (params, "lightDecay",        0.18);

            boolean tiltShiftEnabled = boolParam(params, "tiltShiftEnabled", true);
            double  focusYFrac       = dblParam (params, "focusY",            0.5);
            double  tiltShiftMaxSigma= dblParam (params, "tiltShiftMaxSigma", 8.0);
            double  tiltShiftP       = dblParam (params, "tiltShiftP",        1.0);

            boolean chromaEnabled    = boolParam(params, "chromaEnabled",    true);
            double  chromaRed        = dblParam (params, "chromaRed",         0.005);
            double  chromaBlue       = dblParam (params, "chromaBlue",       -0.003);

            boolean grainEnabled     = boolParam(params, "grainEnabled",     true);
            double  grainSigma       = dblParam (params, "grainSigma",        4.0);

            int  rows = src.rows();
            int  cols = src.cols();
            double cx = cols / 2.0;
            double cy = rows / 2.0;
            int  ch   = src.channels();

            Mat current = src.clone();

            // ── STEP 0: Barrel Distortion ─────────────────────────────────────
            //
            // Uniform radial backward remap applied to all channels at once.
            // Unlike chromatic aberration (per-channel κ), barrel distortion
            // uses a single (k1, k2) pair shared across B, G, and R.
            //
            //   r² = (X² + Y²) / norm²    norm = max(cx, cy)
            //   denom = 1 + k1·r² + k2·r⁴
            //   x_src = cx + X / denom     (division → source pulled inward → barrel)
            //   y_src = cy + Y / denom
            //
            // INTER_LANCZOS4 avoids aliasing at the slightly curved document
            // borders; BORDER_REPLICATE prevents dark or white halos.
            if (barrelEnabled && Math.abs(barrelK1) > 1e-6) {

                double norm  = Math.max(cx, cy);
                double norm2 = norm * norm;
                if (norm2 < 1.0) norm2 = 1.0;

                Mat mapX = new Mat(rows, cols, CvType.CV_32F);
                Mat mapY = new Mat(rows, cols, CvType.CV_32F);

                for (int y = 0; y < rows; y++) {
                    double Y  = y - cy;
                    double Y2 = Y * Y;
                    float[] rowMX = new float[cols];
                    float[] rowMY = new float[cols];
                    for (int x = 0; x < cols; x++) {
                        double X     = x - cx;
                        double r2    = (X * X + Y2) / norm2;
                        double denom = 1.0 + barrelK1 * r2 + barrelK2 * (r2 * r2);
                        // Clamp to avoid divide-by-zero when k1 is negative (pincushion)
                        if (denom < 0.05) denom = 0.05;
                        double invD  = 1.0 / denom;
                        rowMX[x] = (float)(cx + X * invD);
                        rowMY[x] = (float)(cy + Y * invD);
                    }
                    mapX.put(y, 0, rowMX);
                    mapY.put(y, 0, rowMY);
                }

                Mat barrelResult = new Mat();
                Imgproc.remap(current, barrelResult, mapX, mapY,
                              Imgproc.INTER_LANCZOS4, Core.BORDER_REPLICATE, Scalar.all(0));
                mapX.release();
                mapY.release();
                current.release();
                current = barrelResult;
            }

            // ── STEP 1: Affine Transform ──────────────────────────────────────
            //
            // Combines rotation θ and shear into a single 2×3 matrix that keeps
            // the image centre fixed:
            //   a11 = cosθ + shearX·sinθ    a12 = −sinθ + shearX·cosθ
            //   a21 = sinθ + shearY·cosθ    a22 =  cosθ − shearY·sinθ
            //   b1 = cx − (a11·cx + a12·cy) b2 = cy − (a21·cx + a22·cy)
            if (affineEnabled && (Math.abs(affineAngle)  > 0.001 ||
                                  Math.abs(affineShearX) > 0.001 ||
                                  Math.abs(affineShearY) > 0.001)) {

                double theta = Math.toRadians(affineAngle);
                double cosT  = Math.cos(theta);
                double sinT  = Math.sin(theta);

                double a11 = cosT + affineShearX * sinT;
                double a12 = -sinT + affineShearX * cosT;
                double a21 = sinT + affineShearY * cosT;
                double a22 = cosT - affineShearY * sinT;

                // Centre-preserving translation
                double b1  = cx - (a11 * cx + a12 * cy);
                double b2  = cy - (a21 * cx + a22 * cy);

                Mat M = new Mat(2, 3, CvType.CV_64F);
                M.put(0, 0, a11, a12, b1,
                            a21, a22, b2);

                int border = borderMode(borderStr);
                Mat warped = new Mat();
                Imgproc.warpAffine(current, warped, M, current.size(),
                                   Imgproc.INTER_LANCZOS4, border, Scalar.all(0));
                M.release();
                current.release();
                current = warped;
            }

            // ── STEP 2: Luminance Gradient ───────────────────────────────────
            //
            // Builds a 2-D normalised distance field d(x,y) ∈ [0,1] from virtual
            // light source (lx, ly).  Then applies element-wise:
            //   scaleMat = 1 − lightDecay · d   (via convertTo with β=1.0)
            //   current  = current · scaleMat
            if (lightEnabled && lightDecay > 0.001) {

                double lx = lightX * (cols - 1);
                double ly = lightY * (rows - 1);

                // Rmax = distance from light source to the farthest of the 4 corners
                double rmax = 0.0;
                int[][] corners = {{0, 0}, {cols - 1, 0}, {0, rows - 1}, {cols - 1, rows - 1}};
                for (int[] corner : corners) {
                    double dx = corner[0] - lx, dy = corner[1] - ly;
                    rmax = Math.max(rmax, Math.sqrt(dx * dx + dy * dy));
                }
                if (rmax < 1.0) rmax = 1.0;

                // Build distance Mat via batch row puts (rows JNI calls, not rows*cols)
                Mat distMat = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    double dy2 = (y - ly) * (y - ly);
                    float[] rowData = new float[cols];
                    for (int x = 0; x < cols; x++) {
                        double dx = x - lx;
                        rowData[x] = (float)(Math.sqrt(dx * dx + dy2) / rmax);
                    }
                    distMat.put(y, 0, rowData);
                }

                // scaleMat = 1.0 − lightDecay · distMat   (convertTo: α·src + β)
                Mat scaleMat = new Mat();
                distMat.convertTo(scaleMat, CvType.CV_32F, -lightDecay, 1.0);
                distMat.release();

                // Clamp scale to [0, 1]
                Mat zeros = Mat.zeros(rows, cols, CvType.CV_32F);
                Mat ones  = Mat.ones(rows, cols, CvType.CV_32F);
                Core.max(scaleMat, zeros, scaleMat);
                Core.min(scaleMat, ones,  scaleMat);
                zeros.release();
                ones.release();

                // Replicate to match channel count and multiply
                Mat scaleC   = replicateToChannels(scaleMat, ch);
                scaleMat.release();

                Mat currentF = new Mat();
                current.convertTo(currentF, CvType.CV_32F);
                Mat litF = new Mat();
                Core.multiply(currentF, scaleC, litF);
                currentF.release();
                scaleC.release();

                Mat lit = new Mat();
                litF.convertTo(lit, current.type());
                litF.release();
                current.release();
                current = lit;
            }

            // ── STEP 2b: Specular Highlight (Micro-Reflection) ───────────────
            //
            // Adds a narrow additive Gaussian bright spot simulating specular
            // reflection from a point light source on matte paper fibres.
            // Even matte surfaces have micro-facets that produce a localised
            // brightness peak rather than a flat illumination field.
            //
            //   sx = specX · (cols−1),  sy = specY · (rows−1)
            //   σ_s = specSigma · min(rows, cols)
            //   spot(x,y) = specStrength · exp(−((x−sx)² + (y−sy)²) / (2·σ_s²))
            //   I'(x,y)   = clamp(I(x,y) + 255·spot(x,y), 0, 255)
            //
            // Placed before tilt-shift so DoF blur naturally softens the spot
            // toward the unfocused edge — matching real photographic behaviour.
            if (specEnabled && specStrength > 0.001) {

                double sx    = specX * (cols - 1);
                double sy    = specY * (rows - 1);
                double sigma = specSigma * Math.min(rows, cols);
                if (sigma < 1.0) sigma = 1.0;
                double inv2s2 = 1.0 / (2.0 * sigma * sigma);

                Mat spotMono = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    double dy  = y - sy;
                    double dy2 = dy * dy;
                    float[] rowData = new float[cols];
                    for (int x = 0; x < cols; x++) {
                        double dx = x - sx;
                        rowData[x] = (float)(specStrength * 255.0
                                             * Math.exp(-(dx * dx + dy2) * inv2s2));
                    }
                    spotMono.put(y, 0, rowData);
                }

                Mat spotC = replicateToChannels(spotMono, ch);
                spotMono.release();

                Mat currentF = new Mat();
                current.convertTo(currentF, CvType.CV_32F);
                Core.add(currentF, spotC, currentF);
                spotC.release();

                // Clamp to [0, 255]
                Imgproc.threshold(currentF, currentF, 255.0, 255.0, Imgproc.THRESH_TRUNC);

                Mat specResult = new Mat();
                currentF.convertTo(specResult, current.type());
                currentF.release();
                current.release();
                current = specResult;
            }

            // ── STEP 3: Tilt-Shift Space-Variant Gaussian Blur ───────────────
            //
            // Power-law alpha map:  α(y) = (|y − focusRow| / maxDist)^p
            // Blend:  result = sharp · (1 − α)  +  blurred_max · α
            if (tiltShiftEnabled && tiltShiftMaxSigma >= 0.5) {

                int focusRow = (int) Math.round(focusYFrac * (rows - 1));

                // Build the fully-blurred reference at σ = tiltShiftMaxSigma
                int maxKSize = Math.max(3, 2 * (int) Math.ceil(3.0 * tiltShiftMaxSigma) + 1);
                if (maxKSize % 2 == 0) maxKSize++;

                Mat fullyBlurred = new Mat();
                Imgproc.GaussianBlur(current, fullyBlurred,
                                     new Size(maxKSize, maxKSize), tiltShiftMaxSigma);

                // Power-law alpha: distance from focus row normalised to [0, 1]
                double maxDist = Math.max(focusRow, rows - 1 - focusRow);
                if (maxDist < 1.0) maxDist = 1.0;

                Mat alphaMat = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    double normDist = Math.abs(y - focusRow) / maxDist;
                    float  alpha    = (float) Math.pow(normDist, tiltShiftP);
                    alphaMat.row(y).setTo(new Scalar(alpha));
                }

                // Vectorised blend: result = sharp*(1−α) + blurred*α  (no pixel loops)
                Mat invAlpha = new Mat(rows, cols, CvType.CV_32F);
                Mat ones     = Mat.ones(alphaMat.size(), CvType.CV_32F);
                Core.subtract(ones, alphaMat, invAlpha);
                ones.release();

                Mat alphaC    = replicateToChannels(alphaMat,  ch);
                Mat invAlphaC = replicateToChannels(invAlpha,  ch);
                alphaMat.release();
                invAlpha.release();

                Mat sharpF   = new Mat();  current.convertTo(sharpF,   CvType.CV_32F);
                Mat blurredF = new Mat();  fullyBlurred.convertTo(blurredF, CvType.CV_32F);
                fullyBlurred.release();

                Mat sharpW   = new Mat();  Core.multiply(sharpF,   invAlphaC, sharpW);
                Mat blurredW = new Mat();  Core.multiply(blurredF, alphaC,    blurredW);
                Mat resultF  = new Mat();  Core.add(sharpW, blurredW, resultF);
                sharpF.release();   blurredF.release();
                sharpW.release();   blurredW.release();
                alphaC.release();   invAlphaC.release();

                Mat tiltResult = new Mat();
                resultF.convertTo(tiltResult, current.type());
                resultF.release();
                current.release();
                current = tiltResult;
            }

            // ── STEP 4: Chromatic Aberration (Radial Channel Fringing) ────────
            //
            // Backward radial remap per channel:
            //   x_src = cx + (x − cx) / (1 + κ · r²)
            //   y_src = cy + (y − cy) / (1 + κ · r²)
            //   r²    = ((x−cx)² + (y−cy)²) / norm²
            //
            // R uses chromaRed (k > 0 → outward / barrel)
            // B uses chromaBlue (k < 0 → inward / pincushion)
            // G is unchanged (κ = 0)
            if (chromaEnabled && ch == 3 &&
                (Math.abs(chromaRed) > 1e-6 || Math.abs(chromaBlue) > 1e-6)) {

                double norm = Math.max(cx, cy);
                if (norm < 1.0) norm = 1.0;
                double norm2 = norm * norm;

                // Allocate all four remap tables
                Mat mapXR = new Mat(rows, cols, CvType.CV_32F);
                Mat mapYR = new Mat(rows, cols, CvType.CV_32F);
                Mat mapXB = new Mat(rows, cols, CvType.CV_32F);
                Mat mapYB = new Mat(rows, cols, CvType.CV_32F);

                // Build tables via batch row puts (rows JNI calls per table)
                for (int y = 0; y < rows; y++) {
                    double Y = y - cy;
                    float[] rowXRData = new float[cols];
                    float[] rowYRData = new float[cols];
                    float[] rowXBData = new float[cols];
                    float[] rowYBData = new float[cols];

                    for (int x = 0; x < cols; x++) {
                        double X  = x - cx;
                        double r2 = (X * X + Y * Y) / norm2;

                        // κ > 0 → denominator > 1 → source coordinates pulled toward centre
                        // → image pixels pushed outward (barrel-like for that channel)
                        // Clamp denominators to ≥ 0.05 to prevent singularity when |κ| is large.
                        double denomR = 1.0 + chromaRed  * r2;
                        double denomB = 1.0 + chromaBlue * r2;
                        if (denomR < 0.05) denomR = 0.05;
                        if (denomB < 0.05) denomB = 0.05;
                        double scaleR = 1.0 / denomR;
                        double scaleB = 1.0 / denomB;

                        rowXRData[x] = (float)(cx + X * scaleR);
                        rowYRData[x] = (float)(cy + Y * scaleR);
                        rowXBData[x] = (float)(cx + X * scaleB);
                        rowYBData[x] = (float)(cy + Y * scaleB);
                    }

                    mapXR.put(y, 0, rowXRData);
                    mapYR.put(y, 0, rowYRData);
                    mapXB.put(y, 0, rowXBData);
                    mapYB.put(y, 0, rowYBData);
                }

                // Split into B=0, G=1, R=2 channels (OpenCV BGR order)
                List<Mat> bgrChannels = new ArrayList<>();
                Core.split(current, bgrChannels);

                // Use INTER_CUBIC (4×4 kernel) for single-channel remaps.
                // INTER_LANCZOS4 (8×8 sinc kernel) produces Gibbs ringing at
                // high-contrast text edges when applied to individual colour planes,
                // manifesting as coloured halos.  INTER_CUBIC is sharper than
                // bilinear and free of the Lanczos overshoot for this use-case.
                Mat rWarped = new Mat();
                Imgproc.remap(bgrChannels.get(2), rWarped, mapXR, mapYR,
                              Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar.all(0));

                Mat bWarped = new Mat();
                Imgproc.remap(bgrChannels.get(0), bWarped, mapXB, mapYB,
                              Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar.all(0));

                mapXR.release();  mapYR.release();
                mapXB.release();  mapYB.release();

                // Merge: B_warped, G_unchanged, R_warped
                List<Mat> merged = new ArrayList<>();
                merged.add(bWarped);
                merged.add(bgrChannels.get(1).clone());
                merged.add(rWarped);

                Mat chromaResult = new Mat();
                Core.merge(merged, chromaResult);

                for (Mat m : bgrChannels) m.release();
                // merged holds bWarped (index 0), G_clone (index 1), rWarped (index 2).
                // Release all three here — do NOT release rWarped/bWarped separately.
                for (Mat m : merged) m.release();

                current.release();
                current = chromaResult;
            }

            // ── STEP 5: ISO Grain (Additive Gaussian Noise) ──────────────────
            //
            // I''(x,y) = clamp(I'(x,y) + N(0, σ_grain), 0, 255)
            // Core.randn fills each channel independently — no colour cast.
            if (grainEnabled && grainSigma > 0.1) {
                // Always generate mono (luminance) grain and replicate to all channels.
                // Per-channel independent noise would introduce random colour casts;
                // photographic grain has no inherent colour component.
                Mat grainMono = new Mat(rows, cols, CvType.CV_32F);
                Core.randn(grainMono, 0.0, grainSigma);
                Mat noiseF = replicateToChannels(grainMono, ch);
                grainMono.release();

                Mat currentF = new Mat();
                current.convertTo(currentF, CvType.CV_32F);
                Core.add(currentF, noiseF, currentF);
                noiseF.release();

                // Clamp to [0, 255] via threshold
                Imgproc.threshold(currentF, currentF, 255.0, 255.0, Imgproc.THRESH_TRUNC);
                Imgproc.threshold(currentF, currentF,   0.0,   0.0, Imgproc.THRESH_TOZERO);

                Mat grained = new Mat();
                currentF.convertTo(grained, current.type());
                currentF.release();
                current.release();
                current = grained;
            }

            return current;
        });

    // -----------------------------------------------------------------------
    // Replicates a single-channel CV_32F Mat across n channels via merge.
    // When n == 1 the Mat is cloned and returned immediately.
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

    // ── parameter helpers ──────────────────────────────────────────────────

    private static double dblParam(Map<String, Object> p, String key, double def) {
        Object v = p.get(key);
        return v == null ? def : ((Number) v).doubleValue();
    }

    private static boolean boolParam(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    private static String strParam(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : v.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers — map human-readable strings to OpenCV integer flags
    // -----------------------------------------------------------------------
    private static int borderMode(String name) {
        if (name == null) return Core.BORDER_REPLICATE;
        return switch (name.toLowerCase()) {
            case "constant"           -> Core.BORDER_CONSTANT;
            case "reflect"            -> Core.BORDER_REFLECT;
            case "reflect101", "wrap" -> Core.BORDER_REFLECT_101;
            default                   -> Core.BORDER_REPLICATE;
        };
    }
}
