package com.example.currencydetector

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class RecognitionResult(
    val label: String,
    val displayTitle: String,
    val confidence: Float,
)

class CurrencyClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels = listOf<String>()

    private var inputWidth = 224
    private var inputHeight = 224
    private var isFloatInput = true
    private var isChannelFirst = false

    init {
        setupClassifier()
    }

    private fun setupClassifier() {
        try {
            val labelFile = if (context.assets.list("")?.contains("label.txt") == true) "label.txt" else "labels.txt"
            context.assets.open(labelFile).bufferedReader().useLines { lines ->
                labels = lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }

            val assetFileDescriptor = context.assets.openFd("best.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(modelBuffer, options)

            interpreter?.let { tflite ->
                val inputTensor = tflite.getInputTensor(0)
                val shape = inputTensor.shape()
                if ((shape.size != 4) || (shape[0] != 1)) {
                    throw IllegalArgumentException("Expected a batch-1, 4D image input tensor.")
                }
                when {
                    shape[1] == 3 -> {
                        isChannelFirst = true
                        inputHeight = shape[2]
                        inputWidth = shape[3]
                    }
                    shape[3] == 3 -> {
                        inputHeight = shape[1]
                        inputWidth = shape[2]
                    }
                    else -> throw IllegalArgumentException("Expected a 3-channel image input tensor.")
                }
                isFloatInput = inputTensor.dataType() == DataType.FLOAT32
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun classifyImage(bitmap: Bitmap): RecognitionResult {
        val tflite = interpreter
        if ((tflite == null) || labels.isEmpty()) {
            return RecognitionResult("Not Detected", "Not Detected", 0.0f)
        }

        try {
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val bytesPerChannel = if (isFloatInput) 4 else 1
            val inputBuffer = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * 3 * bytesPerChannel)
            inputBuffer.order(ByteOrder.nativeOrder())

            val intValues = IntArray(inputWidth * inputHeight)
            resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

            if (isChannelFirst) {
                for (channel in 0..2) {
                    for (value in intValues) {
                        val channelValue = when (channel) {
                            0 -> (value shr 16) and 0xFF
                            1 -> (value shr 8) and 0xFF
                            else -> value and 0xFF
                        }
                        if (isFloatInput) {
                            inputBuffer.putFloat(channelValue / 255.0f)
                        } else {
                            inputBuffer.put(channelValue.toByte())
                        }
                    }
                }
            } else {
                for (value in intValues) {
                    val r = (value shr 16) and 0xFF
                    val g = (value shr 8) and 0xFF
                    val b = value and 0xFF
                    if (isFloatInput) {
                        inputBuffer.putFloat(r / 255.0f)
                        inputBuffer.putFloat(g / 255.0f)
                        inputBuffer.putFloat(b / 255.0f)
                    } else {
                        inputBuffer.put(r.toByte())
                        inputBuffer.put(g.toByte())
                        inputBuffer.put(b.toByte())
                    }
                }
            }
            inputBuffer.rewind()

            val outputTensor = tflite.getOutputTensor(0)
            val outputShape = outputTensor.shape()

            var maxIndex = -1
            var maxConfidence = 0.0f

            if ((outputShape.size == 2) && (outputShape[0] == 1)) {
                val numClasses = outputShape[1]
                val outputScores = Array(1) { FloatArray(numClasses) }
                tflite.run(inputBuffer, outputScores)

                for (i in 0 until numClasses) {
                    if (outputScores[0][i] > maxConfidence) {
                        maxConfidence = outputScores[0][i]
                        maxIndex = i
                    }
                }
            } else if (outputShape.size == 3) {
                val dim1 = outputShape[1]
                val dim2 = outputShape[2]

                if (dim1 < dim2) {
                    val outputArray = Array(1) { Array(dim1) { FloatArray(dim2) } }
                    tflite.run(inputBuffer, outputArray)

                    val numClasses = dim1 - 4
                    if (numClasses > 0) {
                        val classScores = FloatArray(numClasses)
                        for (box in 0 until dim2) {
                            for (c in 0 until numClasses) {
                                val score = outputArray[0][4 + c][box]
                                if (score > classScores[c]) {
                                    classScores[c] = score
                                }
                            }
                        }
                        for (c in 0 until numClasses) {
                            if (classScores[c] > maxConfidence) {
                                maxConfidence = classScores[c]
                                maxIndex = c
                            }
                        }
                    }
                } else {
                    val outputArray = Array(1) { Array(dim1) { FloatArray(dim2) } }
                    tflite.run(inputBuffer, outputArray)

                    val numClasses = dim2 - 4
                    if (numClasses > 0) {
                        val classScores = FloatArray(numClasses)
                        for (box in 0 until dim1) {
                            for (c in 0 until numClasses) {
                                val score = outputArray[0][box][4 + c]
                                if (score > classScores[c]) {
                                    classScores[c] = score
                                }
                            }
                        }
                        for (c in 0 until numClasses) {
                            if (classScores[c] > maxConfidence) {
                                maxConfidence = classScores[c]
                                maxIndex = c
                            }
                        }
                    }
                }
            }

            if ((maxIndex >= 0) && (maxIndex < labels.size)) {
                val detectedLabel = labels[maxIndex]
                val formattedTitle = "₹$detectedLabel Detected"
                return RecognitionResult(detectedLabel, formattedTitle, maxConfidence)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return RecognitionResult("Not Detected", "Not Detected", 0.0f)
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
