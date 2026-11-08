package dev.r1agents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.InputStream

object PairingQr {
    fun image(pairing: Pairing, size: Int = 360): Bitmap {
        val matrix = QRCodeWriter().encode(pairing.link(), BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    fun read(open: () -> InputStream?): Pairing {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "immagine non leggibile" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: throw IllegalArgumentException("immagine non leggibile")
        return try { read(bitmap) } finally { bitmap.recycle() }
    }
    fun read(bitmap: Bitmap): Pairing {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        val reader = MultiFormatReader()
        val decoded = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
            .getOrElse { reader.reset(); runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrElse { throw IllegalArgumentException("QR non trovato: avvicina la fotocamera e riprova") } }
        return Pairing.parse(decoded)
    }
}
