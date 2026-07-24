package to.bitkit.ui.screens.scanner

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class BarcodeModelUnavailableException(cause: Throwable? = null) :
    Exception("The QR scanner model is unavailable", cause)

internal class BarcodeModelInstaller(
    private val scanner: BarcodeScanner,
    private val moduleInstallClient: ModuleInstallClient,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val callbackExecutor: Executor = Executor { it.run() },
) : AutoCloseable {
    private val finished = AtomicBoolean()
    private val listenerLock = Any()
    private var installStatusListener: InstallStatusListener? = null

    fun start() {
        moduleInstallClient.areModulesAvailable(scanner)
            .addOnSuccessListener(callbackExecutor) { availability ->
                if (finished.get()) {
                    return@addOnSuccessListener
                }

                if (availability.areModulesAvailable()) {
                    finishReady()
                } else {
                    install()
                }
            }
            .addOnFailureListener(callbackExecutor, ::finishError)
    }

    private fun install() {
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finishReady()
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED,
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                -> finishError(BarcodeModelUnavailableException())
            }
        }

        val request = ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .setListener(listener, callbackExecutor)
            .build()

        val installTask = synchronized(listenerLock) {
            if (finished.get()) {
                return
            }
            installStatusListener = listener
            moduleInstallClient.installModules(request)
        }

        installTask
            .addOnSuccessListener(callbackExecutor) { response ->
                if (response.areModulesAlreadyInstalled()) {
                    finishReady()
                }
            }
            .addOnFailureListener(callbackExecutor, ::finishError)
    }

    private fun finishReady() {
        if (finished.compareAndSet(false, true)) {
            unregisterListener()
            onReady()
        }
    }

    private fun finishError(error: Throwable) {
        if (finished.compareAndSet(false, true)) {
            unregisterListener()
            onError(
                if (error is BarcodeModelUnavailableException) {
                    error
                } else {
                    BarcodeModelUnavailableException(error)
                }
            )
        }
    }

    private fun unregisterListener() {
        val listener = synchronized(listenerLock) {
            installStatusListener.also { installStatusListener = null }
        }
        listener?.let(moduleInstallClient::unregisterListener)
    }

    override fun close() {
        if (finished.compareAndSet(false, true)) {
            unregisterListener()
        }
    }
}

internal fun createQrScanner(): BarcodeScanner {
    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    return BarcodeScanning.getClient(options)
}

internal fun scanQrImage(
    context: Context,
    image: InputImage,
    onScanSuccess: (String) -> Unit,
    onNoQrCode: () -> Unit,
    onError: (Throwable) -> Unit,
) {
    val scanner = createQrScanner()
    BarcodeModelInstaller(
        scanner = scanner,
        moduleInstallClient = ModuleInstall.getClient(context),
        onReady = {
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qrCode = barcodes.firstNotNullOfOrNull { it.rawValue }
                    if (qrCode == null) {
                        onNoQrCode()
                    } else {
                        onScanSuccess(qrCode)
                    }
                }
                .addOnFailureListener(onError)
                .addOnCompleteListener { scanner.close() }
        },
        onError = {
            scanner.close()
            onError(it)
        },
        callbackExecutor = ContextCompat.getMainExecutor(context),
    ).start()
}
