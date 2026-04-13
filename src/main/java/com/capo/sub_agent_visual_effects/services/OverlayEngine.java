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
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class OverlayEngine {

    // -----------------------------------------------------------------------
    // All overlay / composite rendering operations.
    // Keys are lower-cased so applyOperation(name.toLowerCase()) always hits.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry = Map.ofEntries(

        // 1. Watermark — renders semi-transparent text over the image.
        //
        //    Two modes:
        //      tiled=false (default) — one large stamp centered on the image,
        //                              rotated by `angle` around the image centre.
        //      tiled=true            — staggered repeating grid of stamps covers
        //                              the entire image after rotation.
        //
        //    Rotation technique: text is drawn on an oversized canvas padded by
        //    the full image diagonal (tiled) or on a same-size canvas (single),
        //    then warpAffine rotates the canvas, and the result is cropped back
        //    to the original image dimensions — guaranteeing corner coverage.
        //
        //    Blending technique: a binary mask marks every text pixel on the
        //    rotated overlay.  Only those pixels are replaced in the output:
        //      dst[text] = src[text] * (1 - opacity) + textColor * opacity
        //      dst[rest] = src[rest]   (pixel-perfect, no global darkening)
        //
        //    params:
        //      text       (String,  "CONFIDENTIAL") — stamp text
        //      opacity    (double,  0.25)           — 0 = invisible, 1 = opaque
        //      angle      (double, -45.0)           — rotation degrees (neg = CCW)
        //      fontScale  (double,  4.0)            — OpenCV HERSHEY_SIMPLEX scale
        //      colorR/G/B (int,    180)             — text colour in RGB
        //      tiled      (boolean, false)          — repeat text across image
        //      thickness  (int,     3)              — stroke thickness in pixels
        Map.entry("watermark", (src, params) -> {
            String  text      = (String)  params.getOrDefault("text",       "CONFIDENTIAL");
            double  opacity   = ((Number) params.getOrDefault("opacity",     0.25)).doubleValue();
            double  angle     = ((Number) params.getOrDefault("angle",      -45.0)).doubleValue();
            double  fontScale = ((Number) params.getOrDefault("fontScale",    4.0)).doubleValue();
            int     colorR    = ((Number) params.getOrDefault("colorR",      180)).intValue();
            int     colorG    = ((Number) params.getOrDefault("colorG",      180)).intValue();
            int     colorB    = ((Number) params.getOrDefault("colorB",      180)).intValue();
            boolean tiled     = Boolean.TRUE.equals(params.getOrDefault("tiled", false));
            int     thickness = ((Number) params.getOrDefault("thickness",     3)).intValue();

            int    rows  = src.rows();
            int    cols  = src.cols();
            Scalar color = new Scalar(colorB, colorG, colorR); // OpenCV is BGR

            // 1. Build text-only overlay (black background, colored text, rotated)
            Mat overlay = buildTextOverlay(rows, cols, text, fontScale, thickness,
                                           color, tiled, angle);

            // 2. Build single-channel mask: 255 where text was painted, 0 elsewhere
            Mat gray = new Mat();
            Imgproc.cvtColor(overlay, gray, Imgproc.COLOR_BGR2GRAY);
            Mat mask = new Mat();
            Imgproc.threshold(gray, mask, 1, 255, Imgproc.THRESH_BINARY);
            gray.release();

            // 3. Blend at text pixels only
            //    blended = src*(1-opacity) + overlay*opacity
            //    At non-text pixels overlay=0 so blended = src*(1-opacity) — darker,
            //    but we discard those with the mask and restore from original src.
            Mat blended = new Mat();
            Core.addWeighted(src, 1.0 - opacity, overlay, opacity, 0.0, blended);
            overlay.release();

            // 4. dst = src everywhere; paste blended only where mask is non-zero
            Mat dst = src.clone();
            blended.copyTo(dst, mask);
            blended.release();
            mask.release();

            return dst;
        })
    );

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (src, p) -> src)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Watermark helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a BGR text-only overlay Mat sized {@code rows × cols}.
     *
     * <p><b>Tiled mode</b>: draws a staggered grid of text stamps on a canvas
     * padded by the full image diagonal so that all corner areas remain covered
     * after rotation.  The canvas is rotated around its own centre and then
     * cropped back to the original image dimensions.</p>
     *
     * <p><b>Single-stamp mode</b>: draws one stamp centred on the canvas, rotates
     * around the image centre, and returns the same-sized canvas.</p>
     *
     * @param rows      target image height in pixels
     * @param cols      target image width in pixels
     * @param text      watermark string
     * @param fontScale OpenCV FONT_HERSHEY_SIMPLEX scale factor
     * @param thickness stroke thickness in pixels
     * @param color     BGR scalar for text colour
     * @param tiled     if {@code true}, repeat text in a staggered grid
     * @param angle     rotation in degrees (negative = counter-clockwise)
     * @return BGR Mat (same dimension as source) with text on black background
     */
    private static Mat buildTextOverlay(int rows, int cols, String text,
                                        double fontScale, int thickness, Scalar color,
                                        boolean tiled, double angle) {

        // Measure text bounding box
        int[] baseLine = new int[1];
        Size textSize = Imgproc.getTextSize(
                text, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, thickness, baseLine);
        int tw = (int) textSize.width;
        int th = (int) textSize.height;

        // For tiled mode: enlarge the working canvas by the image diagonal so that
        // the tiled grid completely covers all four corners after any rotation angle.
        int pad     = tiled ? (int) Math.ceil(0.6 * Math.hypot(rows, cols)) : 0;
        int bigRows = rows + 2 * pad;
        int bigCols = cols + 2 * pad;

        Mat canvas = Mat.zeros(bigRows, bigCols, CvType.CV_8UC3);

        if (tiled) {
            // Staggered horizontal grid: every other row is offset by half spacingX
            // to avoid a rigid rectangular pattern that looks unnatural.
            int spacingX = Math.max(1, (int) (tw * 2.2));
            int spacingY = Math.max(1, (int) (th * 4.0));
            int rowIdx = 0;
            for (int y = 0; y < bigRows + spacingY; y += spacingY, rowIdx++) {
                int xOffset = (rowIdx % 2 == 0) ? 0 : (spacingX / 2);
                for (int x = xOffset - tw; x < bigCols + tw; x += spacingX) {
                    // putText origin = bottom-left corner of the text baseline in OpenCV
                    Imgproc.putText(canvas, text, new Point(x, y + th),
                            Imgproc.FONT_HERSHEY_SIMPLEX, fontScale,
                            color, thickness, Imgproc.LINE_AA, false);
                }
            }
        } else {
            // Single stamp centred on the working canvas
            // (bigCanvas centre == image centre when pad==0)
            double cx = (bigCols - tw) / 2.0;
            double cy = (bigRows + th) / 2.0;
            Imgproc.putText(canvas, text, new Point(cx, cy),
                    Imgproc.FONT_HERSHEY_SIMPLEX, fontScale,
                    color, thickness, Imgproc.LINE_AA, false);
        }

        // Rotate the whole working canvas around its own centre
        if (Math.abs(angle) > 0.01) {
            Point center = new Point(bigCols / 2.0, bigRows / 2.0);
            Mat   rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0);
            Mat   rotated = Mat.zeros(bigRows, bigCols, canvas.type());
            Imgproc.warpAffine(canvas, rotated, rotMat, new Size(bigCols, bigRows));
            canvas.release();
            canvas = rotated;
        }

        // Crop back to original image size by extracting the centre region
        if (pad > 0) {
            Mat cropped = canvas.submat(new Rect(pad, pad, cols, rows)).clone();
            canvas.release();
            return cropped;
        }
        return canvas;
    }
}
