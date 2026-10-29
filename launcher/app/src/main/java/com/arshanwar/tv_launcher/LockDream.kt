package com.arshanwar.tv_launcher

import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.View

/** System screensaver. Interactive only so ◀ ▶ can skip aerials; every other button (volume aside) wakes it. */
class LockDream : DreamService() {
    private var content: View? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        val v = Saver.build(this, false)
        content = v
        isInteractive = v is AerialView
        setContentView(v)
        current = this
    }
    override fun onDetachedFromWindow() { if (current === this) current = null; super.onDetachedFromWindow() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val aerial = content as? AerialView ?: return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false   // the system changes volume
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) aerial.skip(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            else -> if (event.action == KeyEvent.ACTION_DOWN) finish()
        }
        return true
    }

    companion object { @Volatile var current: LockDream? = null }
}
