package com.canilhice.assistant

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var listen: MaterialButton
    private lateinit var robot: ImageView
    private lateinit var glow: View
    private lateinit var keyInput: EditText
    private lateinit var keyLayout: TextInputLayout
    private lateinit var detail: TextView
    private lateinit var appName: TextView
    private var active = false
    private var robotAnimator: Animator? = null
    private var nameAnimator: Animator? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startAssistant()
        else Toast.makeText(this, "A permissão do microfone é necessária.", Toast.LENGTH_LONG).show()
    }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val newStatus = intent?.getStringExtra(VoiceAssistantService.EXTRA_STATUS) ?: return
            status.text = newStatus.lineSequence().first()
            val message = intent.getStringExtra(VoiceAssistantService.EXTRA_ANSWER)
            detail.text = message.orEmpty()
            detail.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            animateRobot(intent.getStringExtra(VoiceAssistantService.EXTRA_STATE) ?: VoiceAssistantService.STATE_IDLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        listen = findViewById(R.id.listenButton)
        robot = findViewById(R.id.robotImage)
        glow = findViewById(R.id.robotGlow)
        keyInput = findViewById(R.id.apiKeyInput)
        keyLayout = findViewById(R.id.apiKeyLayout)
        detail = findViewById(R.id.detailText)
        appName = findViewById(R.id.appNameText)

        val hasKey = SecureKeyStore(this).load() != null
        if (hasKey) keyLayout.hint = getString(R.string.api_key_saved)
        active = VoiceAssistantService.isActive(this)
        renderActiveState()
        animateRobot(if (active) VoiceAssistantService.STATE_LISTENING else VoiceAssistantService.STATE_IDLE)

        listen.setOnClickListener {
            if (active) stopAssistant() else saveKeyAndRequestStart()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            updates,
            IntentFilter(VoiceAssistantService.ACTION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(updates)
        super.onStop()
    }

    override fun onDestroy() {
        robotAnimator?.cancel()
        nameAnimator?.cancel()
        super.onDestroy()
    }

    private fun saveKeyAndRequestStart() {
        val typedKey = keyInput.text.toString().trim()
        if (typedKey.isNotBlank()) {
            if (!typedKey.startsWith("sk-") || typedKey.length < 20) {
                Toast.makeText(this, "Insira uma chave válida.", Toast.LENGTH_SHORT).show()
                return
            }
            SecureKeyStore(this).save(typedKey)
            keyInput.text.clear()
            keyLayout.hint = getString(R.string.api_key_saved)
        }
        if (SecureKeyStore(this).load() == null) {
            Toast.makeText(this, "Digite sua chave da OpenAI.", Toast.LENGTH_LONG).show()
            return
        }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissions.launch(needed.toTypedArray())
        } else {
            startAssistant()
        }
    }

    private fun startAssistant() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.START)
        )
        active = true
        renderActiveState()
        animateRobot(VoiceAssistantService.STATE_LISTENING)
    }

    private fun stopAssistant() {
        startService(Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.STOP))
        active = false
        renderActiveState()
        animateRobot(VoiceAssistantService.STATE_IDLE)
    }

    private fun renderActiveState() {
        listen.text = getString(if (active) R.string.pause else R.string.listen)
        status.text = getString(if (active) R.string.status_listening else R.string.status_paused)
        listen.alpha = if (active) 0.82f else 1f
    }

    private fun animateRobot(state: String) {
        robotAnimator?.cancel()
        nameAnimator?.cancel()
        robot.rotation = 0f
        robot.scaleX = 1f
        robot.scaleY = 1f
        glow.scaleX = 1f
        glow.scaleY = 1f
        glow.alpha = 0.28f

        robotAnimator = when (state) {
            VoiceAssistantService.STATE_LISTENING -> pulseAnimation(0.42f, 1.16f, 650L)
            VoiceAssistantService.STATE_THINKING -> thinkingAnimation()
            VoiceAssistantService.STATE_SPEAKING -> pulseAnimation(0.55f, 1.09f, 380L)
            VoiceAssistantService.STATE_ERROR -> errorAnimation()
            else -> pulseAnimation(0.22f, 1.04f, 1700L)
        }.also { it.start() }

        val titleScale = if (state == VoiceAssistantService.STATE_LISTENING) 1.045f else 1.025f
        val titleDuration = if (state == VoiceAssistantService.STATE_SPEAKING) 420L else 900L
        val titleX = ObjectAnimator.ofFloat(appName, View.SCALE_X, 1f, titleScale).repeatForever()
        val titleY = ObjectAnimator.ofFloat(appName, View.SCALE_Y, 1f, titleScale).repeatForever()
        val titleAlpha = ObjectAnimator.ofFloat(appName, View.ALPHA, 0.72f, 1f).repeatForever()
        nameAnimator = AnimatorSet().apply {
            playTogether(titleX, titleY, titleAlpha)
            duration = titleDuration
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun pulseAnimation(targetAlpha: Float, targetScale: Float, duration: Long): AnimatorSet {
        val glowX = ObjectAnimator.ofFloat(glow, View.SCALE_X, 1f, targetScale).repeatForever()
        val glowY = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 1f, targetScale).repeatForever()
        val glowAlpha = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.18f, targetAlpha).repeatForever()
        val robotX = ObjectAnimator.ofFloat(robot, View.SCALE_X, 1f, 1.025f).repeatForever()
        val robotY = ObjectAnimator.ofFloat(robot, View.SCALE_Y, 1f, 1.025f).repeatForever()
        return AnimatorSet().apply {
            playTogether(glowX, glowY, glowAlpha, robotX, robotY)
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    private fun thinkingAnimation(): AnimatorSet {
        val rotate = ObjectAnimator.ofFloat(robot, View.ROTATION, -4f, 4f).repeatForever()
        val glowX = ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.9f, 1.2f).repeatForever()
        val glowY = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 1.2f, 0.9f).repeatForever()
        return AnimatorSet().apply {
            playTogether(rotate, glowX, glowY)
            duration = 520L
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    private fun errorAnimation(): AnimatorSet {
        val shake = ObjectAnimator.ofFloat(robot, View.TRANSLATION_X, 0f, -12f, 12f, -8f, 8f, 0f)
        return AnimatorSet().apply {
            play(shake)
            duration = 520L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    robot.translationX = 0f
                }
            })
        }
    }

    private fun ObjectAnimator.repeatForever(): ObjectAnimator = apply {
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }
}
