package com.example.currencydetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var currencyImageView: ImageView
    private lateinit var resultCardOverlay: LinearLayout
    private lateinit var tvDetectedResult: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var btnSpeakResult: ImageButton

    private lateinit var currencyClassifier: CurrencyClassifier
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var tempImageUri: Uri? = null

    private var currentSpeechText = "500 Rupees Detected"

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success: Boolean ->
        val currentUri = tempImageUri
        if (success && (currentUri != null)) {
            onImageCaptured(currentUri)
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required to capture photos",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val selectGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            onImageCaptured(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left + dpToPx(20),
                systemBars.top + dpToPx(16),
                systemBars.right + dpToPx(20),
                systemBars.bottom + dpToPx(16),
            )
            insets
        }

        currencyImageView = findViewById(R.id.currencyImageView)
        resultCardOverlay = findViewById(R.id.resultCardOverlay)
        tvDetectedResult = findViewById(R.id.tvDetectedResult)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        btnSpeakResult = findViewById(R.id.btnSpeakResult)

        val btnCamera = findViewById<MaterialButton>(R.id.btnCamera)
        val btnGallery = findViewById<MaterialButton>(R.id.btnGallery)

        currencyClassifier = CurrencyClassifier(this)
        textToSpeech = TextToSpeech(this, this)

        btnCamera.setOnClickListener {
            checkAndLaunchCamera()
        }

        btnGallery.setOnClickListener {
            selectGalleryLauncher.launch("image/*")
        }

        btnSpeakResult.setOnClickListener {
            speakCurrentResult()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.forLanguageTag("hi-IN"))
            if ((result == TextToSpeech.LANG_MISSING_DATA) || (result == TextToSpeech.LANG_NOT_SUPPORTED)) {
                textToSpeech?.language = Locale.ENGLISH
            }
            isTtsInitialized = true
        }
    }

    private fun onImageCaptured(uri: Uri) {
        currencyImageView.setImageDrawable(null)
        currencyImageView.setImageURI(uri)
        currencyImageView.setPadding(0, 0, 0, 0)

        val bitmap = getBitmapFromUri(uri)
        if (bitmap != null) {
            val recognition = currencyClassifier.classifyImage(bitmap)
            tvDetectedResult.text = recognition.displayTitle
            currentSpeechText = "${recognition.label} Rupees Detected"

            resultCardOverlay.visibility = View.VISIBLE
            tvAiStatus.text = getString(R.string.ai_analyzed)
            tvConfidence.text = getString(R.string.confidence_high)

            speakCurrentResult()
        }
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun speakCurrentResult() {
        if (isTtsInitialized) {
            textToSpeech?.speak(currentSpeechText, TextToSpeech.QUEUE_FLUSH, null, "CURRENCY_TTS_ID")
        }
    }

    private fun checkAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("temp_currency") && file.name.endsWith(".jpg")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val photoFile = File(cacheDir, "temp_currency_${System.currentTimeMillis()}.jpg")
        tempImageUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            photoFile,
        )
        tempImageUri?.let { uri ->
            takePictureLauncher.launch(uri)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        currencyClassifier.close()
        if (textToSpeech != null) {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        super.onDestroy()
    }
}
