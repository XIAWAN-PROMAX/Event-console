package com.matchconsole.sender

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.matchconsole.common.Logx
import com.matchconsole.common.PermissionUtils
import com.matchconsole.console.ConsoleButton
import com.matchconsole.console.SideAccent
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 二维码扫描页：发送端扫描接收端屏幕上显示的 matchconsole://ip:port。
 *
 * 相机能力用 CameraX + MLKit，不引入第三方扫码库。
 */
@Composable
fun QrScanScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionGranted by remember {
        mutableStateOf(PermissionUtils.granted(context, Manifest.permission.CAMERA))
    }
    val analyzer = remember { QrCodeAnalyzer { raw -> onResult(raw) } }

    DisposableEffect(Unit) {
        onDispose { analyzer.close() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (permissionGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val executor = ContextCompat.getMainExecutor(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        try {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { it.setAnalyzer(executor, analyzer) }

                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        } catch (t: Throwable) {
                            Logx.e("相机初始化失败", t)
                        }
                    }, executor)
                    previewView
                }
            )

            // 取景框
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
                    .border(2.dp, SideAccent.Home, RoundedCornerShape(16.dp))
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "需要相机权限才能扫码",
                    color = Color(0xFFE5EAF2),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                ConsoleButton(
                    text = "已授权，重新检测",
                    onClick = {
                        permissionGranted = PermissionUtils.granted(context, Manifest.permission.CAMERA)
                    },
                    containerColor = SideAccent.Info,
                    contentColor = Color.White
                )
            }
        }

        Text(
            text = "对准接收端屏幕上的二维码",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConsoleButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(0.5f),
                containerColor = SideAccent.Neutral,
                contentColor = Color.White,
                height = 50.dp
            )
        }
    }
}

/**
 * 单帧二维码分析器。
 *
 * 命中一次后置位 handled，避免同一二维码被连续回调多次导致重复连接。
 */
private class QrCodeAnalyzer(
    private val onFound: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @Volatile
    private var handled = false

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (handled) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                if (!handled && !raw.isNullOrBlank()) {
                    handled = true
                    Logx.i("扫码结果: $raw")
                    onFound(raw)
                }
            }
            .addOnFailureListener { t ->
                Logx.w("扫码失败: ${t.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        try {
            scanner.close()
        } catch (_: Throwable) {
        }
    }
}