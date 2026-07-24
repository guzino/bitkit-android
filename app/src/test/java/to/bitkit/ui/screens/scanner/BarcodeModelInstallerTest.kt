package to.bitkit.ui.screens.scanner

import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BarcodeModelInstallerTest {
    private val scanner: BarcodeScanner = mock()
    private val moduleInstallClient: ModuleInstallClient = mock()

    @Test
    fun `reports ready when the barcode module is already available`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(
                ModuleAvailabilityResponse(
                    true,
                    ModuleAvailabilityResponse.AvailabilityStatus.STATUS_ALREADY_AVAILABLE,
                )
            )
        )
        var readyCalls = 0

        BarcodeModelInstaller(
            scanner = scanner,
            moduleInstallClient = moduleInstallClient,
            onReady = { readyCalls++ },
            onError = {},
        ).start()

        assertEquals(1, readyCalls)
        verify(moduleInstallClient, never()).installModules(org.mockito.kotlin.any())
    }

    @Test
    fun `waits for module installation before reporting ready`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(
                ModuleAvailabilityResponse(
                    false,
                    ModuleAvailabilityResponse.AvailabilityStatus.STATUS_READY_TO_DOWNLOAD,
                )
            )
        )
        whenever(moduleInstallClient.installModules(org.mockito.kotlin.any())).thenReturn(
            Tasks.forResult(ModuleInstallResponse(7, false))
        )
        var readyCalls = 0

        BarcodeModelInstaller(
            scanner = scanner,
            moduleInstallClient = moduleInstallClient,
            onReady = { readyCalls++ },
            onError = {},
        ).start()

        val request = argumentCaptor<ModuleInstallRequest>().apply {
            verify(moduleInstallClient).installModules(capture())
        }.firstValue
        assertEquals(0, readyCalls)
        request.listener?.onInstallStatusUpdated(
            ModuleInstallStatusUpdate(
                7,
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED,
                null,
                null,
                0,
            )
        )

        assertEquals(1, readyCalls)
    }

    @Test
    fun `reports a model error when module installation cannot start`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(
                ModuleAvailabilityResponse(
                    false,
                    ModuleAvailabilityResponse.AvailabilityStatus.STATUS_READY_TO_DOWNLOAD,
                )
            )
        )
        whenever(moduleInstallClient.installModules(org.mockito.kotlin.any())).thenReturn(
            Tasks.forException(IllegalStateException("offline"))
        )
        var error: Throwable? = null

        BarcodeModelInstaller(
            scanner = scanner,
            moduleInstallClient = moduleInstallClient,
            onReady = {},
            onError = { error = it },
        ).start()

        assertIs<BarcodeModelUnavailableException>(error)
    }

    @Test
    fun `does not start module installation after close`() {
        val availability = TaskCompletionSource<ModuleAvailabilityResponse>()
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(availability.task)
        val installer = BarcodeModelInstaller(
            scanner = scanner,
            moduleInstallClient = moduleInstallClient,
            onReady = {},
            onError = {},
        )

        installer.start()
        installer.close()
        availability.setResult(
            ModuleAvailabilityResponse(
                false,
                ModuleAvailabilityResponse.AvailabilityStatus.STATUS_READY_TO_DOWNLOAD,
            )
        )

        verify(moduleInstallClient, never()).installModules(org.mockito.kotlin.any())
    }

    @Test
    fun `close unregisters an active installation listener once`() {
        whenever(moduleInstallClient.areModulesAvailable(scanner)).thenReturn(
            Tasks.forResult(
                ModuleAvailabilityResponse(
                    false,
                    ModuleAvailabilityResponse.AvailabilityStatus.STATUS_READY_TO_DOWNLOAD,
                )
            )
        )
        val installation = TaskCompletionSource<ModuleInstallResponse>()
        whenever(moduleInstallClient.installModules(org.mockito.kotlin.any())).thenReturn(installation.task)
        val installer = BarcodeModelInstaller(
            scanner = scanner,
            moduleInstallClient = moduleInstallClient,
            onReady = {},
            onError = {},
        )

        installer.start()
        val request = argumentCaptor<ModuleInstallRequest>().apply {
            verify(moduleInstallClient).installModules(capture())
        }.firstValue
        val listener = requireNotNull(request.listener)
        installer.close()
        installer.close()

        verify(moduleInstallClient, times(1)).unregisterListener(listener)
    }
}
