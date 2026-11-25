package xyz.aresius.audiobooks.feature.onboarding

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface ScanResult {
    data class Text(val value: String) : ScanResult
    data object Cancelled : ScanResult

    /** No Google Play services / scanner module, or it failed: the screen falls back to paste. */
    data object Unavailable : ScanResult
}

/** Scans one QR code with the Google code scanner (a Play services screen: no camera permission in this app). */
suspend fun scanQr(context: Context): ScanResult = suspendCancellableCoroutine { cont ->
    val resume = { r: ScanResult -> if (cont.isActive) cont.resume(r) }
    try {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> resume(barcode.rawValue?.let { ScanResult.Text(it) } ?: ScanResult.Cancelled) }
            .addOnCanceledListener { resume(ScanResult.Cancelled) }
            .addOnFailureListener { e ->
                resume(if ((e as? MlKitException)?.errorCode == MlKitException.CODE_SCANNER_CANCELLED) ScanResult.Cancelled else ScanResult.Unavailable)
            }
    } catch (_: Exception) {
        resume(ScanResult.Unavailable)
    }
}
