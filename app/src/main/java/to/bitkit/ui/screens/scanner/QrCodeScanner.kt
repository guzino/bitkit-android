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

internal class BarcodeModelUnavailableException(cause: Throwable? = null) :
    Exception("The QR scanner model is unavailable", cause)

internal fun retryQrModelIfUnavailable(
    isUnavailable: Boolean,
    retry: () -> Boolean,
): Boolean = isUnavailable && !retry()

internal class BarcodeModelInstaller(
    private val scanner: BarcodeScanner,
    private val moduleInstallClient: ModuleInstallClient,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val callbackExecutor: Executor = Executor { it.run() },
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private var ready = false
    private var attemptActive = false
    private var attemptId = 0L
    private var installStatusListener: InstallStatusListener? = null

    fun start(): Boolean = startAttempt()

    fun retry(): Boolean = startAttempt()

    private fun startAttempt(): Boolean {
        val currentAttemptId = synchronized(stateLock) {
            if (closed || ready || attemptActive) {
                return false
            }
            attemptActive = true
            ++attemptId
        }

        moduleInstallClient.areModulesAvailable(scanner)
            .addOnSuccessListener(callbackExecutor) { availability ->
                if (!isCurrentAttempt(currentAttemptId)) {
                    return@addOnSuccessListener
                }

                if (availability.areModulesAvailable()) {
                    finishReady(currentAttemptId)
                } else {
                    install(currentAttemptId)
                }
            }
            .addOnFailureListener(callbackExecutor) { finishError(currentAttemptId, it) }

        return true
    }

    private fun install(currentAttemptId: Long) {
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finishReady(currentAttemptId)
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED,
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                -> finishError(currentAttemptId, BarcodeModelUnavailableException())
            }
        }

        val request = ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .setListener(listener, callbackExecutor)
            .build()

        val installTask = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(currentAttemptId)) {
                return
            }
            installStatusListener = listener
            moduleInstallClient.installModules(request)
        }

        installTask
            .addOnSuccessListener(callbackExecutor) { response ->
                if (response.areModulesAlreadyInstalled()) {
                    finishReady(currentAttemptId)
                }
            }
            .addOnFailureListener(callbackExecutor) { finishError(currentAttemptId, it) }
    }

    private fun finishReady(currentAttemptId: Long) {
        val listener = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(currentAttemptId)) {
                return
            }
            ready = true
            attemptActive = false
            installStatusListener.also { installStatusListener = null }
        }
        listener?.let(moduleInstallClient::unregisterListener)
        onReady()
    }

    private fun finishError(currentAttemptId: Long, error: Throwable) {
        val listener = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(currentAttemptId)) {
                return
            }
            attemptActive = false
            installStatusListener.also { installStatusListener = null }
        }
        listener?.let(moduleInstallClient::unregisterListener)
        onError(
            if (error is BarcodeModelUnavailableException) {
                error
            } else {
                BarcodeModelUnavailableException(error)
            }
        )
    }

    private fun isCurrentAttempt(currentAttemptId: Long) = synchronized(stateLock) {
        isCurrentAttemptLocked(currentAttemptId)
    }

    private fun isCurrentAttemptLocked(currentAttemptId: Long) =
        !closed && !ready && attemptActive && attemptId == currentAttemptId

    override fun close() {
        val listener = synchronized(stateLock) {
            if (closed) {
                return
            }
            closed = true
            attemptActive = false
            ++attemptId
            installStatusListener.also { installStatusListener = null }
        }
        listener?.let(moduleInstallClient::unregisterListener)
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
) = QrImageScanOperation(
    scanner = createQrScanner(),
    moduleInstallClient = ModuleInstall.getClient(context),
    image = image,
    callbacks = QrImageScanCallbacks(
        onScanSuccess = onScanSuccess,
        onNoQrCode = onNoQrCode,
        onError = onError,
    ),
    callbackExecutor = ContextCompat.getMainExecutor(context),
).also { it.start() }

internal class QrImageScanCallbacks(
    val onScanSuccess: (String) -> Unit,
    val onNoQrCode: () -> Unit,
    val onError: (Throwable) -> Unit,
)

internal class QrImageScanOperation(
    private val scanner: BarcodeScanner,
    moduleInstallClient: ModuleInstallClient,
    private val image: InputImage,
    private val callbacks: QrImageScanCallbacks,
    private val callbackExecutor: Executor = Executor { it.run() },
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private var processing = false
    private var completed = false
    private val modelInstaller = BarcodeModelInstaller(
        scanner = scanner,
        moduleInstallClient = moduleInstallClient,
        onReady = ::processImage,
        onError = ::reportModelError,
        callbackExecutor = callbackExecutor,
    )

    fun start(): Boolean = modelInstaller.start()

    fun retryModelInstallation(): Boolean {
        synchronized(stateLock) {
            if (closed || completed || processing) {
                return false
            }
        }
        return modelInstaller.retry()
    }

    private fun processImage() {
        val shouldProcess = synchronized(stateLock) {
            if (closed || completed || processing) {
                false
            } else {
                processing = true
                true
            }
        }
        if (!shouldProcess) {
            return
        }

        scanner.process(image)
            .addOnSuccessListener(callbackExecutor) { barcodes ->
                val qrCode = barcodes.firstNotNullOfOrNull { it.rawValue }
                finish {
                    if (qrCode == null) {
                        callbacks.onNoQrCode()
                    } else {
                        callbacks.onScanSuccess(qrCode)
                    }
                }
            }
            .addOnFailureListener(callbackExecutor) { error ->
                finish { callbacks.onError(error) }
            }
    }

    private fun reportModelError(error: Throwable) {
        val shouldReport = synchronized(stateLock) {
            !closed && !completed && !processing
        }
        if (shouldReport) {
            callbacks.onError(error)
        }
    }

    private fun finish(callback: () -> Unit) {
        val shouldFinish = synchronized(stateLock) {
            if (closed || completed) {
                false
            } else {
                completed = true
                true
            }
        }
        if (!shouldFinish) {
            return
        }

        try {
            callback()
        } finally {
            close()
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldClose) {
            return
        }
        modelInstaller.close()
        scanner.close()
    }
}
