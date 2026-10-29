package com.arshanwar.tv_launcher

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class LockActivity : Activity() {
    private lateinit var content: View
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content = Saver.build(this, true, intent.getStringExtra("mode"))   // test hook: --es mode aerials
        setContentView(content)
    }
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        val aerial = content as? AerialView
        when {
            aerial != null && k == KeyEvent.KEYCODE_DPAD_LEFT -> aerial.skip(-1)
            aerial != null && k == KeyEvent.KEYCODE_DPAD_RIGHT -> aerial.skip(1)
            k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE -> return super.onKeyDown(k, e)
            else -> finish()
        }
        return true
    }
    override fun onTouchEvent(e: MotionEvent?): Boolean { finish(); return true }
}
