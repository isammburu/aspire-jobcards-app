package com.aspirelending.jobcards.core

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object Ocr {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    suspend fun read(ctx: Context, uri: Uri): List<OcrLine> =
        rec.process(InputImage.fromFilePath(ctx, uri)).await().let { toLines(it) }
    suspend fun readBitmap(b: android.graphics.Bitmap): List<OcrLine> = toLines(rec.process(InputImage.fromBitmap(b, 0)).await())
    private fun toLines(t: com.google.mlkit.vision.text.Text): List<OcrLine> =
        t.textBlocks.flatMap { it.lines }
            .mapNotNull { l -> l.boundingBox?.let { OcrLine(l.text, it, l.confidence) } }
}
