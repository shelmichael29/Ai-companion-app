package com.aicarcompanion.app.vin.android

import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aicarcompanion.app.vin.RecognizedLine
import com.aicarcompanion.app.vin.ScanBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live camera preview with on-device text recognition (CameraX + ML Kit, bundled model).
 *
 * Privacy and battery:
 * - Frames are analyzed in memory and closed at once. No picture or video is ever saved or sent.
 * - Analysis runs about 5 times a second, only while [analyzing] is true, and stops when the
 *   screen leaves the composition (the camera is unbound).
 *
 * Each result reports the recognized lines with their boxes plus the upright size of the analyzed
 * image, so the caller can draw a highlight over the preview ([com.aicarcompanion.app.vin.ScanGeometry]).
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class) // the AndroidX annotation, which is the one Lint checks for
@Composable
fun VinCameraView(
    torchOn: Boolean,
    analyzing: Boolean,
    onLines: (lines: List<RecognizedLine>, imageWidth: Int, imageHeight: Int) -> Unit,
    onTorchAvailable: (Boolean) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analyzingNow by rememberUpdatedState(analyzing)
    val onLinesNow by rememberUpdatedState(onLines)
    val onTorchAvailableNow by rememberUpdatedState(onTorchAvailable)
    val onErrorNow by rememberUpdatedState(onCameraError)

    DisposableEffect(lifecycleOwner) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var analysisUseCase: ImageAnalysis? = null
        // Set first thing on dispose. The camera provider can finish starting after the screen has
        // already closed; without this it would bind the camera to the Activity and leave it on.
        val disposed = AtomicBoolean(false)

        providerFuture.addListener({
            if (disposed.get()) return@addListener
            try {
                val cameraProvider = providerFuture.get().also { provider = it }
                // Same 16:9 shape for the preview and the analysis, so a box found in the analyzed
                // image lines up with what's on screen.
                val previewSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()
                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
                    .also { it.surfaceProvider = previewView.surfaceProvider; previewUseCase = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysisUseCase = it }

                var lastRun = 0L
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val image = proxy.image
                        val now = SystemClock.elapsedRealtime()
                        if (disposed.get() || !analyzingNow || image == null || now - lastRun < MIN_FRAME_GAP_MILLIS) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        lastRun = now
                        val rotation = proxy.imageInfo.rotationDegrees
                        val upright = if (rotation % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
                        recognizer.process(InputImage.fromMediaImage(image, rotation))
                            .addOnSuccessListener { text ->
                                if (disposed.get()) return@addOnSuccessListener
                                val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                                    RecognizedLine(line.text, line.boundingBox?.let { ScanBox(it.left, it.top, it.right, it.bottom) })
                                }
                                onLinesNow(lines, upright.first, upright.second)
                            }
                            .addOnCompleteListener { proxy.close() } // the frame is released here, never stored
                    } catch (e: Exception) {
                        // A failure here must not leak the frame (CameraX would stop delivering) or crash the app.
                        proxy.close()
                    }
                }

                val bound = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                if (disposed.get()) { // closed while binding: undo at once so the camera doesn't stay on
                    cameraProvider.unbind(preview, analysis)
                    return@addListener
                }
                camera = bound
                onTorchAvailableNow(bound.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                onErrorNow()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed.set(true)
            analysisUseCase?.clearAnalyzer()
            // Our own use cases only. Unbinding the last ones also turns the flashlight off.
            provider?.unbind(*listOfNotNull(previewUseCase, analysisUseCase).toTypedArray())
            camera = null
            recognizer.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private const val MIN_FRAME_GAP_MILLIS = 200L
