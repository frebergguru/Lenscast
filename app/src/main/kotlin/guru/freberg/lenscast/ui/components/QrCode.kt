package guru.freberg.lenscast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Modal showing a URL as a scannable QR code. Used by the Connection card so a second
 * device can grab the stream URL without typing it. White QR on a white panel so the
 * camera contrast stays clean even on dark-mode parents.
 */
@Composable
fun QrCodeDialog(url: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to open") },
        text = {
            Column {
                // White card so the QR has a quiet zone regardless of theme — scanners
                // need ~4-module padding to lock on reliably.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCanvas(
                        url = url,
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun QrCanvas(url: String, modifier: Modifier = Modifier) {
    // Encode once; ErrorCorrectionLevel.M is the sweet spot (15% recovery) for short URLs
    // that contain digits + a few special chars.
    val matrix = remember(url) {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        writer.encode(url, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
    Canvas(modifier = modifier.fillMaxSize().background(Color.White)) {
        val w = matrix.width
        val h = matrix.height
        if (w == 0 || h == 0) return@Canvas
        val moduleSize = minOf(size.width / w, size.height / h)
        val offsetX = (size.width  - moduleSize * w) / 2f
        val offsetY = (size.height - moduleSize * h) / 2f
        for (y in 0 until h) for (x in 0 until w) {
            if (!matrix[x, y]) continue
            drawRect(
                color = Color.Black,
                topLeft = androidx.compose.ui.geometry.Offset(
                    offsetX + x * moduleSize,
                    offsetY + y * moduleSize,
                ),
                size = androidx.compose.ui.geometry.Size(moduleSize, moduleSize),
            )
        }
    }
}
