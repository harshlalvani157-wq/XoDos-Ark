package app.xodos2.ui.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import java.io.File

@RequiresApi(Build.VERSION_CODES.P)
private object Api28ImageDecoderHelper {
    fun decode(file: File): Drawable? {
        val source = ImageDecoder.createSource(file)
        val d = ImageDecoder.decodeDrawable(source)
        if (d is AnimatedImageDrawable) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
        return d
    }
}

/**
 * Background view — static images + animated GIF / WebP / APNG.
 *
 * Priority:
 *   API 28+ → ImageDecoder (handles every animated format correctly)
 *   older   → Movie (GIF only, limited)
 *   last    → BitmapFactory (single frame)
 *
 * Rendering: **STRETCH** — the image fills the entire view exactly,
 * aspect ratio is not preserved, no cropping, no letterboxing.
 *
 * Cache key includes file size + last-modified so picking a different image
 * that saves to the same path (e.g. another GIF at terminal_background.gif)
 * triggers a fresh reload — this is what makes the background refresh
 * automatically right after you tap Save in the editor.
 */
class AnimatedBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var drawable: Drawable? = null
    private var movieStartTime: Long = 0L
    private var currentKey: String? = null

    fun setSource(path: String?) {
        // Cache key = path + size + mtime, so a new image saved over the
        // same file path still forces a reload.
        val key: String? = if (path.isNullOrBlank()) {
            null
        } else {
            val f = File(path)
            if (f.isFile) "$path|${f.length()}|${f.lastModified()}" else null
        }

        if (currentKey == key) return
        currentKey = key

        clearInternal()

        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "setSource: not a file — $path")
            return
        }

        // ── API 28+: ImageDecoder handles every animated format ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val d = Api28ImageDecoderHelper.decode(file)
                if (d != null) {
                    d.callback = this
                    drawable = d

                    // Preset bounds so any draw attempt is already full-size
                    d.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))

                    val visible = isAttachedToWindow && isShown
                    d.setVisible(visible, false)
                    if (d is Animatable && visible) d.start()

                    Log.i(TAG, "ImageDecoder: ${d.javaClass.simpleName} " +
                            "intrinsic=${d.intrinsicWidth}x${d.intrinsicHeight} $path")
                    invalidate()
                    return
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ImageDecoder failed, trying Movie/Bitmap", e)
            }
        }

        // ── Fallback: Movie (mostly for API < 28) ──
        try {
            val m = Movie.decodeFile(path)
            if (m != null && m.width() > 0 && m.height() > 0) {
                movie = m
                movieStartTime = SystemClock.uptimeMillis()
                Log.i(TAG, "Movie: ${m.width()}x${m.height()} $path")
                invalidate()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Movie failed", e)
        }

        // ── Static fallback ──
        BitmapFactory.decodeFile(path)?.let { bmp ->
            val d = BitmapDrawable(resources, bmp).also {
                it.callback = this
                it.setVisible(isAttachedToWindow && isShown, false)
                it.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            }
            drawable = d
            Log.i(TAG, "Bitmap: ${bmp.width}x${bmp.height} $path")
            invalidate()
        } ?: Log.e(TAG, "Could not decode $path with any method")
    }

    fun clear() {
        currentKey = null
        clearInternal()
    }

    private fun clearInternal() {
        (drawable as? Animatable)?.stop()
        drawable?.setVisible(false, false)
        drawable = null
        movie = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawable?.setBounds(0, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // ── Drawable path (static + animated) ──
        drawable?.let { d ->
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight

            if (iw > 0 && ih > 0) {
                // Explicit STRETCH: draw at intrinsic size in a scaled canvas.
                val scaleX = width.toFloat() / iw
                val scaleY = height.toFloat() / ih
                canvas.save()
                canvas.scale(scaleX, scaleY)
                d.setBounds(0, 0, iw, ih)
                d.draw(canvas)
                canvas.restore()
            } else {
                d.setBounds(0, 0, width, height)
                d.draw(canvas)
            }

            (d as? Animatable)?.let { anim ->
                // Safety net: keep frames advancing
                if (!d.isVisible) d.setVisible(true, false)
                if (!anim.isRunning) anim.start()
                postInvalidateOnAnimation()
            }
            return
        }

        // ── Movie path ──
        movie?.let { m ->
            val now = SystemClock.uptimeMillis()
            val duration = if (m.duration() > 0) m.duration() else 1000
            val relTime = ((now - movieStartTime) % duration).toInt()
            m.setTime(relTime)

            if (m.width() > 0 && m.height() > 0) {
                val scaleX = width.toFloat() / m.width()
                val scaleY = height.toFloat() / m.height()
                canvas.save()
                canvas.scale(scaleX, scaleY)
                m.draw(canvas, 0f, 0f)
                canvas.restore()
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        val visible = visibility == View.VISIBLE && isAttachedToWindow
        drawable?.setVisible(visible, false)
        (drawable as? Animatable)?.let { anim ->
            if (visible) anim.start()
            else anim.stop()
        }
        if (movie != null && visible) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drawable?.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        drawable?.setVisible(isShown, false)
        if (isShown) (drawable as? Animatable)?.start()
        if (movie != null) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drawable?.setVisible(false, false)
        (drawable as? Animatable)?.stop()
    }

    private companion object {
        const val TAG = "AnimatedBg"
    }
}
