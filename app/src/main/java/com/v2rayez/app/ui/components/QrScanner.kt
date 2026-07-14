package com.v2rayez.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.v2rayez.app.R
import java.util.concurrent.Executors

/**
 * Full-screen, QR-code-focused scanner built on CameraX + ML Kit (restricted to
 * [Barcode.FORMAT_QR_CODE]). Unlike a generic barcode reader it renders a square
 * framing reticle with an animated scan line, a dim scrim, and a torch toggle, and it
 * is fully accessible (labelled controls, TalkBack-friendly).
 *
 * [onResult] fires once with the decoded QR text, then the scanner dismisses.
 */
@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    prompt: String = stringResource(R.string.qr_scan_prompt)
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (!granted) onDismiss()
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCameraPermission) {
                var torchOn by remember { mutableStateOf(false) }
                var lensBack by remember { mutableStateOf(true) }
                var handled by remember { mutableStateOf(false) }

                CameraPreview(
                    torchOn = torchOn,
                    lensBack = lensBack,
                    onQrDetected = { value ->
                        if (!handled) {
                            handled = true
                            onResult(value)
                        }
                    }
                )

                QrReticleOverlay(prompt = prompt)

                // Top bar: close + flip + torch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close_scanner), tint = Color.White)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = { torchOn = false; lensBack = !lensBack },
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Filled.Cameraswitch, contentDescription = stringResource(R.string.action_switch_camera), tint = Color.White)
                        }
                        IconButton(
                            onClick = { torchOn = !torchOn },
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                        ) {
                            Icon(
                                if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = if (torchOn) stringResource(R.string.action_flashlight_off) else stringResource(R.string.action_flashlight_on),
                                tint = Color.White
                            )
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.qr_camera_permission_required),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(torchOn: Boolean, lensBack: Boolean, onQrDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Release the camera + executor when the scanner leaves composition, otherwise the
    // camera can stay bound (stuck on) after navigating/closing the dialog.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { previewView.apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
        update = { view ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }

                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(options)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processImage(scanner, imageProxy, onQrDetected)
                }

                val selector = if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImage(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onQrDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull()?.rawValue?.let(onQrDetected)
        }
        .addOnCompleteListener { imageProxy.close() }
}

@Composable
private fun QrReticleOverlay(prompt: String) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanlineProgress"
    )

    val accent = Color(0xFF8B5CF6)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height) * 0.68f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val corner = side * 0.12f
            val stroke = 6.dp.toPx()

            // Dim scrim outside the square (four rectangles).
            val scrim = Color.Black.copy(alpha = 0.55f)
            drawRect(scrim, size = Size(size.width, top))
            drawRect(scrim, topLeft = Offset(0f, top + side), size = Size(size.width, size.height - top - side))
            drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, side))
            drawRect(scrim, topLeft = Offset(left + side, top), size = Size(size.width - left - side, side))

            // Corner brackets.
            fun corner(x0: Float, y0: Float, dx: Int, dy: Int) {
                drawLine(accent, Offset(x0, y0), Offset(x0 + dx * corner, y0), stroke, StrokeCap.Round)
                drawLine(accent, Offset(x0, y0), Offset(x0, y0 + dy * corner), stroke, StrokeCap.Round)
            }
            corner(left, top, 1, 1)
            corner(left + side, top, -1, 1)
            corner(left, top + side, 1, -1)
            corner(left + side, top + side, -1, -1)

            // Animated scan line.
            val y = top + side * progress
            drawLine(
                accent.copy(alpha = 0.9f),
                Offset(left + stroke, y),
                Offset(left + side - stroke, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Column(
            Modifier.fillMaxSize().padding(bottom = 80.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                prompt,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}
