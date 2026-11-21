package com.aoede.twa

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout

/** Decorative video only. It never owns audio focus, a media session, or playback controls. */
internal class NativeViceWallpaper(context: Context, light: Boolean) :
    FrameLayout(context), TextureView.SurfaceTextureListener {
    private val still = NativeViceBackground(context, light)
    private val texture =
        TextureView(context).apply {
            isOpaque = false
            alpha = if (light) .31f else .49f
            visibility = INVISIBLE
            surfaceTextureListener = this@NativeViceWallpaper
        }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    var playing = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    init {
        background = still
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(texture, LayoutParams(-1, -1))
    }

    private fun sync() {
        if (!playing || !isAttachedToWindow || windowVisibility != VISIBLE) {
            release()
            return
        }
        texture.visibility = VISIBLE
        texture.surfaceTexture?.takeIf { texture.isAvailable }?.let(::start)
    }

    private fun start(surfaceTexture: SurfaceTexture) {
        if (!playing || !isAttachedToWindow || windowVisibility != VISIBLE || player != null) return
        try {
            val video = MediaPlayer().also { player = it }
            val output = Surface(surfaceTexture).also { surface = it }
            video.setSurface(output)
            context.assets.openFd(ASSET).use { file ->
                video.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            video.setVolume(0f, 0f)
            video.isLooping = true
            video.setOnVideoSizeChangedListener { _, _, _ -> crop(video) }
            video.setOnPreparedListener {
                if (player === video) video.start()
            }
            video.setOnInfoListener { _, code, _ ->
                if (player === video && code == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    still.showPalms = false
                }
                false
            }
            video.setOnErrorListener { _, _, _ ->
                if (player === video) release()
                true
            }
            video.prepareAsync()
        } catch (_: Exception) {
            release()
        }
    }

    private fun crop(video: MediaPlayer) {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (player !== video || w <= 0 || h <= 0 || video.videoWidth <= 0 || video.videoHeight <= 0) return
        val scale = maxOf(w / video.videoWidth, h / video.videoHeight)
        val transform = Matrix().apply {
            setScale(video.videoWidth * scale / w, video.videoHeight * scale / h, w / 2, h / 2)
        }
        texture.setTransform(transform)
    }

    private fun release() {
        val current = player
        player = null
        current?.release()
        surface?.release()
        surface = null
        texture.visibility = INVISIBLE
        still.showPalms = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sync()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
        start(surface)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        player?.let(::crop)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    companion object {
        const val PATH = "/assets/video/vice-sunset.mp4"
        const val ASSET = "web$PATH"
    }
}
