package com.cappielloantonio.tempo.service.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Copertine dei riquadri della Home di Android Auto nello stile della scheda "Scoperta"
 * dell'app: copertina, sfumatura scura da sinistra e nome della sezione scritto sopra.
 * Le immagini sono servite da [AutoTileProvider]. Da chiamare fuori dal main thread.
 */
object AutoTileArtwork {
    private const val TAG = "AutoTileArtwork"
    private const val SIZE = 512

    fun authority(): String = BuildConfig.APPLICATION_ID + ".autotiles"

    fun dir(context: Context): File = File(context.cacheDir, "auto_tiles").apply { mkdirs() }

    fun render(context: Context, key: String, title: String, cover: Uri?): Uri? {
        return try {
            val name = "${key.filter { it.isLetterOrDigit() }}_${(title + cover).hashCode().toUInt()}.png"
            val file = File(dir(context), name)
            if (!file.exists()) {
                val bitmap = draw(context, title, cover)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            Uri.parse("content://${authority()}/$name")
        } catch (t: Throwable) {
            Log.w(TAG, "Riquadro non generato per $key", t)
            null
        }
    }

    private fun draw(context: Context, title: String, cover: Uri?): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(0x30, 0x33, 0x3A))

        val art: Bitmap? = cover?.let {
            try {
                Glide.with(context).asBitmap().load(it).centerCrop().submit(SIZE, SIZE).get(20, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                null
            }
        }
        art?.let { canvas.drawBitmap(it, null, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG)) }

        // Sfumatura come in "Scoperta": scura a sinistra, trasparente a destra, più un velo in alto
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, SIZE.toFloat(), 0f, Color.argb(0xC0, 0, 0, 0), Color.argb(0x10, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE * 0.6f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, SIZE * 0.6f, Color.argb(0x90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(8f, 0f, 2f, Color.argb(0x80, 0, 0, 0))
        }
        val pad = 40
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, text, SIZE - pad * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return out
    }
}
