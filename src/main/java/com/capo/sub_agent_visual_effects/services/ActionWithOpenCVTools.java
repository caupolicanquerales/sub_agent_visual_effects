package com.capo.sub_agent_visual_effects.services;

import java.util.Base64;
import java.util.Map;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.capo.sub_agent_visual_effects.utils.ImageRefContextHolder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ActionWithOpenCVTools {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ImageSmoothingEngine smoothingEngine;
    private final GeometricTransformEngine geometricEngine;
    private final ThresholdingSegmentationEngine thresholdingEngine;
    private final MorphologicalOperationsEngine morphologicalEngine;
    private final ColorSpaceConversionEngine colorSpaceEngine;
    private final FeatureEdgeDetectionEngine featureEngine;
    private final OverlayEngine overlayEngine;
    private final StainEngine stainEngine;
    private final RealisticBorderEngine realisticBorderEngine;
    private final ProbabilisticBorderEngine probabilisticBorderEngine;
    private final FingerprintEngine fingerprintEngine;
    private final ScanSimulationEngine scanSimulationEngine;
    private final ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(ActionWithOpenCVTools.class);

    public ActionWithOpenCVTools(RedisTemplate<String, Object> redisTemplate,
            ImageSmoothingEngine smoothingEngine,
            GeometricTransformEngine geometricEngine,
            ThresholdingSegmentationEngine thresholdingEngine,
            MorphologicalOperationsEngine morphologicalEngine,
            ColorSpaceConversionEngine colorSpaceEngine,
            FeatureEdgeDetectionEngine featureEngine,
            OverlayEngine overlayEngine,
            StainEngine stainEngine,
            RealisticBorderEngine realisticBorderEngine,
            ProbabilisticBorderEngine probabilisticBorderEngine,
            FingerprintEngine fingerprintEngine,
            ScanSimulationEngine scanSimulationEngine,
            ObjectMapper objectMapper) {
        this.redisTemplate              = redisTemplate;
        this.smoothingEngine            = smoothingEngine;
        this.geometricEngine            = geometricEngine;
        this.thresholdingEngine         = thresholdingEngine;
        this.morphologicalEngine        = morphologicalEngine;
        this.colorSpaceEngine           = colorSpaceEngine;
        this.featureEngine              = featureEngine;
        this.overlayEngine              = overlayEngine;
        this.stainEngine                = stainEngine;
        this.realisticBorderEngine      = realisticBorderEngine;
        this.probabilisticBorderEngine  = probabilisticBorderEngine;
        this.fingerprintEngine          = fingerprintEngine;
        this.scanSimulationEngine       = scanSimulationEngine;
        this.objectMapper               = objectMapper;
    }

    @Tool(description = """
            Applies one OpenCV operation to the source image stored in Redis.
            Call this tool once per operation. For multi-step effects, call it multiple times in sequence.
            The result is stored back in Redis and the new Redis key is returned.
            """)
    public String executingActionWithOpenCVTool(
            @ToolParam(description = """
                    Category of the operation. Accepted values (lower-case, exact match):
                    - 'smoothing'     : image filtering and smoothing operations
                    - 'geometric'     : geometric transformation operations
                    - 'thresholding'  : thresholding and segmentation operations
                    - 'morphological' : morphological operations
                    - 'colorspace'    : color space conversion operations
                    - 'detection'     : feature and edge detection operations
                    - 'overlay'         : composite rendering operations (watermark, text stamp)
                    - 'stain'           : organic substance stain rendering operations
                    - 'realisticborder'     : image-segmentation based realistic border rendering
                    - 'probabilisticborder' : probability-density-driven border damage engine
                    - 'fingerprint'         : procedural Gabor-based fingerprint overlay engine
                    - 'scansimulation'       : homography-based scan tilt simulation with projective shadowing and border noise
                    """) String category,
            @ToolParam(description = """
                    The exact operation key (lower-case) to apply within the chosen category.
                    smoothing       : blur | gaussian | median | bilateral | boxfilter | sqrboxfilter | filter2d | sepfilter2d | stackblur | pyrmeanshift | vignette | scratches | tornborder | wornedge
                    geometric       : resize | flip | rotate | rotatearbitrary | translate | shear | warpaffine | warpperspective | getrectsub | linearpolar | logpolar | remap | undistort
                    thresholding    : threshold | adaptivethreshold | inrange | floodfill | kmeans | connectedcomponents | distancetransform | watershed | grabcut
                    morphological   : erode | dilate | opening | closing | gradient | tophat | blackhat | hitmiss
                    colorspace      : togray | graytobgr | swaprbchannels | tobgra | tobgr | tohsv | fromhsv | tohls | fromhls | tolab | fromlab | toluv | fromluv | toycrcb | fromycrcb | toyuv | fromyuv | toxyz | fromxyz | extractchannel | mergechannels
                    detection       : canny | sobel | scharr | laplacian | prewitt | houghlines | houghcircles | harriscorners | shitomasi | contours | fast | orb
                    overlay         : watermark
                    stain                : stain
                    realisticborder      : segmentborder
                    probabilisticborder  : probabilisticborder
                    fingerprint          : fingerprint
                    scansimulation       : scanrotate | scandeskew
                    """) String operationName,
            @ToolParam(description = """
                    JSON object of parameters for the chosen operation. Use {} for all defaults.
                    Examples:
                      {"ksize": 5}
                      {"ksize": 7, "sigma": 1.5}
                      {"diameter": 9, "sigmaColor": 75, "sigmaSpace": 75}
                      {"width": 800, "height": 600, "interpolation": "linear"}
                      {"angle": 45.0, "scale": 1.0}
                      {"thresh": 127, "maxval": 255, "type": "binary"}
                      {"shape": "ellipse", "ksize": 5}
                      {"colorSpace": "hsv", "lower0": 0, "lower1": 20, "lower2": 70, "upper0": 20, "upper1": 255, "upper2": 255}
                      {"threshold1": 50, "threshold2": 150}
                    """) String paramsJson) {

        String imageKey = ImageRefContextHolder.get();
        if (imageKey == null) {
            log.error("No image reference in context – category={}, op={}", category, operationName);
            return "Error: no source image available in context";
        }

        Object stored = redisTemplate.opsForValue().get(imageKey);
        if (stored == null) {
            log.error("Image not found in Redis for key: {}", imageKey);
            return "Error: source image not found in Redis for key " + imageKey;
        }

        try {
            String raw = stored.toString().trim();
            if (raw.contains(";base64,")) {
                raw = raw.substring(raw.indexOf(";base64,") + 8).trim();
            }
            for (String sig : new String[]{"iVBOR", "/9j/", "Qk", "UklGR"}) {
                int idx = raw.indexOf(sig);
                if (idx >= 0) {
                    raw = raw.substring(idx);
                    break;
                }
            }
            byte[] imageBytes = Base64.getMimeDecoder().decode(raw);
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_UNCHANGED);
            if (src.empty()) {
                return "Error: could not decode image from Redis key " + imageKey;
            }

            // bilateralFilter (and several other ops) require CV_8UC1 or CV_8UC3.
            // If the source PNG has an alpha channel it arrives as CV_8UC4 (BGRA).
            // Drop the alpha here so every engine receives a 3-channel BGR Mat.
            if (src.channels() == 4) {
                Mat bgr = new Mat();
                Imgproc.cvtColor(src, bgr, Imgproc.COLOR_BGRA2BGR);
                src = bgr;
            }

            // Deserialise params
            Map<String, Object> params = paramsJson == null || paramsJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});

            // Dispatch to correct engine
            Mat result = switch (category.toLowerCase()) {
                case "smoothing"     -> smoothingEngine.applyFilter(operationName, src, params);
                case "geometric"     -> geometricEngine.applyTransform(operationName, src, params);
                case "thresholding"  -> thresholdingEngine.applyOperation(operationName, src, params);
                case "morphological" -> morphologicalEngine.applyOperation(operationName, src, params);
                case "colorspace"    -> colorSpaceEngine.applyConversion(operationName, src, params);
                case "detection"     -> featureEngine.applyOperation(operationName, src, params);
                case "overlay"         -> overlayEngine.applyOperation(operationName, src, params);
                case "stain"           -> stainEngine.applyOperation(operationName, src, params);
                case "realisticborder"     -> realisticBorderEngine.applyOperation(operationName, src, params);
                case "probabilisticborder" -> probabilisticBorderEngine.applyOperation(operationName, src, params);
                case "fingerprint"         -> fingerprintEngine.applyOperation(operationName, src, params);
                case "scansimulation"      -> scanSimulationEngine.applyOperation(operationName, src, params);
                default -> {
                    log.error("Unknown category: {}", category);
                    yield src;
                }
            };

            // Encode Mat → base64 and store back in Redis under the same key
            MatOfByte outputBuffer = new MatOfByte();
            Imgcodecs.imencode(".png", result, outputBuffer);
            String resultBase64 = Base64.getEncoder().encodeToString(outputBuffer.toArray());
            redisTemplate.opsForValue().set(imageKey, resultBase64);

            log.info("Applied op='{}' (category='{}') to image at key: {}", operationName, category, imageKey);
            return "Operation '" + operationName + "' (category='" + category + "') applied successfully. Result stored in Redis at key: " + imageKey;

        } catch (Exception e) {
            log.error("Error applying op='{}' (category='{}'): {}", operationName, category, e.getMessage(), e);
            return "Error applying operation '" + operationName + "': " + e.getMessage();
        }
    }
}

