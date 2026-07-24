package to.bitkit.ui.screens.scanner

import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrImageScanOperationTest {
    private val scanner: BarcodeScanner = mock()
    private val moduleInstallClient: ModuleInstallClient = mock()
    private val image: InputImage = mock()

    @Test
    fun `close before availability suppresses processing and callbacks`() {
        val availability = TaskCompletionSource<ModuleAvailabilityResponse>()
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(availability.task)
        var successCalls = 0
        var noCodeCalls = 0
        var errorCalls = 0
        val operation = operation(
            onSuccess = { successCalls++ },
            onNoCode = { noCodeCalls++ },
            onError = { errorCalls++ },
        )

        assertTrue(operation.start())
        operation.close()
        operation.close()
        availability.setResult(availableResponse())

        assertEquals(0, successCalls)
        assertEquals(0, noCodeCalls)
        assertEquals(0, errorCalls)
        verify(scanner, never()).process(image)
        verify(scanner, times(1)).close()
    }

    @Test
    fun `close during installation unregisters and suppresses completion`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(unavailableResponse())
        )
        val installation = TaskCompletionSource<ModuleInstallResponse>()
        whenever(moduleInstallClient.installModules(org.mockito.kotlin.any())).thenReturn(installation.task)
        var successCalls = 0
        var noCodeCalls = 0
        var errorCalls = 0
        val operation = operation(
            onSuccess = { successCalls++ },
            onNoCode = { noCodeCalls++ },
            onError = { errorCalls++ },
        )

        operation.start()
        val request = argumentCaptor<ModuleInstallRequest>().apply {
            verify(moduleInstallClient).installModules(capture())
        }.firstValue
        val listener = requireNotNull(request.listener)
        operation.close()
        listener.onInstallStatusUpdated(
            ModuleInstallStatusUpdate(
                7,
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED,
                null,
                null,
                0,
            )
        )

        assertEquals(0, successCalls)
        assertEquals(0, noCodeCalls)
        assertEquals(0, errorCalls)
        verify(moduleInstallClient, times(1)).unregisterListener(listener)
        verify(scanner, never()).process(image)
        verify(scanner, times(1)).close()
    }

    @Test
    fun `close during image processing suppresses stale scan result`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(availableResponse())
        )
        val scan = TaskCompletionSource<List<Barcode>>()
        whenever(scanner.process(image)).thenReturn(scan.task)
        val barcode: Barcode = mock()
        whenever(barcode.rawValue).thenReturn("bitcoin:example")
        var successCalls = 0
        var noCodeCalls = 0
        var errorCalls = 0
        val operation = operation(
            onSuccess = { successCalls++ },
            onNoCode = { noCodeCalls++ },
            onError = { errorCalls++ },
        )

        operation.start()
        operation.close()
        scan.setResult(listOf(barcode))

        assertEquals(0, successCalls)
        assertEquals(0, noCodeCalls)
        assertEquals(0, errorCalls)
        verify(scanner, times(1)).close()
    }

    @Test
    fun `retry after model failure owns processing through completion`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(unavailableResponse()),
            Tasks.forResult(availableResponse()),
        )
        whenever(moduleInstallClient.installModules(org.mockito.kotlin.any())).thenReturn(
            Tasks.forException(IllegalStateException("offline"))
        )
        val barcode: Barcode = mock()
        whenever(barcode.rawValue).thenReturn("bitcoin:example")
        whenever(scanner.process(image)).thenReturn(Tasks.forResult(listOf(barcode)))
        var successCalls = 0
        var errorCalls = 0
        val operation = operation(
            onSuccess = { successCalls++ },
            onError = { errorCalls++ },
        )

        operation.start()
        assertEquals(1, errorCalls)
        assertEquals(0, successCalls)

        assertTrue(operation.retryModelInstallation())

        assertEquals(1, errorCalls)
        assertEquals(1, successCalls)
        assertFalse(operation.retryModelInstallation())
        verify(scanner, times(1)).process(image)
        verify(scanner, times(1)).close()
    }

    private fun operation(
        onSuccess: (String) -> Unit = {},
        onNoCode: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) = QrImageScanOperation(
        scanner = scanner,
        moduleInstallClient = moduleInstallClient,
        image = image,
        callbacks = QrImageScanCallbacks(
            onScanSuccess = onSuccess,
            onNoQrCode = onNoCode,
            onError = onError,
        ),
    )

    private fun availableResponse() = ModuleAvailabilityResponse(
        true,
        ModuleAvailabilityResponse.AvailabilityStatus.STATUS_ALREADY_AVAILABLE,
    )

    private fun unavailableResponse() = ModuleAvailabilityResponse(
        false,
        ModuleAvailabilityResponse.AvailabilityStatus.STATUS_READY_TO_DOWNLOAD,
    )
}
