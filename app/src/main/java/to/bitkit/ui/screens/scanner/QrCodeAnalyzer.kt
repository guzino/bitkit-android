package to.bitkit.ui.screens.scanner

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger

@OptIn(ExperimentalGetImage::class)
class QrCodeAnalyzer(
    context: Context,
    private val onScanResult: (Result<String>) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val scanCooldownMs = 2000L // 2 seconds cooldown between scans

    @Volatile
    private var isScannerReady = false

    private val scanner: BarcodeScanner = createQrScanner()
    private val modelInstaller = BarcodeModelInstaller(
        scanner = scanner,
        moduleInstallClient = ModuleInstall.getClient(context.applicationContext),
        onReady = { isScannerReady = true },
        onError = { onScanResult(Result.failure(it)) },
        callbackExecutor = ContextCompat.getMainExecutor(context),
    ).also { it.start() }

    fun reset() {
        lastScannedCode = null
        lastScanTime = 0
    }

    override fun analyze(image: ImageProxy) {
        if (!isScannerReady) {
            image.close()
            return
        }

        if (image.image != null) {
            val inputImage = InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnCompleteListener {
                    if (it.isSuccessful) {
                        it.result.let { barcodes ->
                            barcodes.forEach { barcode ->
                                barcode.rawValue?.let { qrCode ->
                                    val currentTime = System.currentTimeMillis()
                                    val isDifferentCode = qrCode != lastScannedCode
                                    val isCooldownExpired = currentTime - lastScanTime > scanCooldownMs

                                    if (isDifferentCode || isCooldownExpired) {
                                        lastScannedCode = qrCode
                                        lastScanTime = currentTime
                                        onScanResult(Result.success(qrCode))
                                    }
                                    image.close()
                                    return@addOnCompleteListener
                                }
                            }
                        }
                    } else {
                        val error = it.exception ?: AppError("Scan failed")
                        Logger.error(error.message.orEmpty(), error)
                        onScanResult(Result.failure(error))
                    }
                    image.close()
                }
        } else {
            image.close()
        }
    }

    override fun close() {
        modelInstaller.close()
        scanner.close()
    }
}
