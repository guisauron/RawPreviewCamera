package com.example.rawpreviewcam // Certifique-se que o package coincide com o seu projeto

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private var imageAnalyzer: ImageAnalysis? = null
    private var lastBitmap: Bitmap? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        val btnSave = findViewById<Button>(R.id.save_button)

        // 1. Pedir permissão
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }

        // 2. Botão de salvar pega o frame atual do analyzer
        btnSave.setOnClickListener {
            saveCurrentFrame()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Configuração do Preview (o que você vê na tela)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // O SEGREDO: ImageAnalysis intercepta os frames crus do preview
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Converte o frame atual para bitmap e guarda na memória
                        val bitmap = imageProxy.toBitmap()
                        if (bitmap != null) {
                            // Ajusta rotação e espelhamento da selfie
                            lastBitmap = rotateAndMirror(bitmap, imageProxy.imageInfo.rotationDegrees)
                        }
                        imageProxy.close()
                    }
                }

            // Força Câmera Frontal
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Toast.makeText(this, "Erro ao abrir câmera", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateAndMirror(source: Bitmap, angle: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle.toFloat())
        // Espelha horizontalmente para a selfie não ficar invertida (como no preview)
        matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun saveCurrentFrame() {
        val bitmapToSave = lastBitmap ?: run {
            Toast.makeText(this, "Aguardando frame...", Toast.LENGTH_SHORT).show()
            return
        }

        val filename = "Preview_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it).use { stream ->
                if (stream != null) {
                    // Salva com qualidade máxima do frame capturado
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                    Toast.makeText(this, "Frame salvo na galeria!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Extensão para converter os dados brutos (YUV) da câmera em Imagem (Bitmap)
    private fun ImageProxy.toBitmap(): Bitmap? {
        val nv21 = yuv420888ToNv21(this)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        return nv21
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}package com.example.rawpreviewcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import androidx.exifinterface.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App mínimo: abre a câmera traseira, mostra o preview num TextureView,
 * e ao apertar o botão salva um JPEG gerado a partir do FRAME DE PREVIEW
 * atual (TEMPLATE_PREVIEW), não de um still capture (TEMPLATE_STILL_CAPTURE).
 *
 * Isso evita o pipeline pesado de processamento de foto (HDR+, multi-frame
 * noise reduction, sharpening agressivo) que a maioria dos fabricantes
 * aplica só na captura "de verdade".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var captureButton: View
    private lateinit var statusText: TextView
    private lateinit var flashOverlay: View

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size = Size(1920, 1080)
    private var cameraId: String = ""
    private var sensorOrientation: Int = 90

    private var latestImage: Image? = null
    private val imageLock = Object()

    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }

    private val requestCameraPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCameraWhenReady()
        } else {
            showStatusBriefly("Permissão de câmera negada")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableImmersiveMode()

        textureView = findViewById(R.id.textureView)
        captureButton = findViewById(R.id.captureButton)
        statusText = findViewById(R.id.statusText)
        flashOverlay = findViewById(R.id.flashOverlay)

        captureButton.setOnClickListener { onCaptureClicked() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    /** Esconde barra de status/navegação pra uma experiência de câmera em tela cheia de verdade. */
    private fun enableImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            if (textureView.isAvailable) {
                openCameraWhenReady()
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCameraWhenReady()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("camera-bg").also { it.start() }
        bgHandler = Handler(bgThread.looper)
    }

    private fun stopBackgroundThread() {
        bgThread.quitSafely()
        try {
            bgThread.join()
        } catch (e: InterruptedException) { }
    }

    private fun openCameraWhenReady() {
        if (!textureView.isAvailable) {
            textureView.surfaceTextureListener = surfaceTextureListener
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            // Escolhe a câmera traseira
            cameraId = cameraManager.cameraIdList.first { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                as StreamConfigurationMap
            previewSize = chooseBestPreviewSize(map)

            imageReader = ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    synchronized(imageLock) {
                        latestImage?.close()
                        latestImage = img
                    }
                }, bgHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    createPreviewSession()
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close(); cameraDevice = null
                    runOnUiThread { showStatusBriefly("Erro ao abrir câmera ($error)") }
                }
            }, bgHandler)

        } catch (e: CameraAccessException) {
            showStatusBriefly("Erro de acesso à câmera")
        }
    }

    /** Mostra um texto discreto no topo por ~2s, com fade in e fade out. */
    private fun showStatusBriefly(text: String) {
        statusText.text = text
        statusText.animate().cancel()
        statusText.alpha = 0f
        statusText.animate().alpha(1f).setDuration(200).withEndAction {
            statusText.postDelayed({
                statusText.animate().alpha(0f).setDuration(400).start()
            }, 1800)
        }.start()
    }

    /** Escolhe o maior preview YUV disponível, limitado a algo razoável (até ~4K). */
    private fun chooseBestPreviewSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf(Size(1920, 1080))
        return sizes
            .filter { it.width <= 3840 && it.height <= 2160 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    private fun createPreviewSession() {
        val cam = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)
        val readerSurface = imageReader?.surface ?: return

        val targets = listOf(previewSurface, readerSurface)

        cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val requestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    addTarget(readerSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    // Deixa o pipeline de preview o mais "limpo" possível
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                }
                try {
                    session.setRepeatingRequest(requestBuilder.build(), null, bgHandler)
                    runOnUiThread { showStatusBriefly("${previewSize.width} × ${previewSize.height}") }
                } catch (e: CameraAccessException) {
                    runOnUiThread { showStatusBriefly("Erro ao iniciar preview") }
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                runOnUiThread { showStatusBriefly("Falha ao configurar sessão da câmera") }
            }
        }, bgHandler)
    }

    private fun onCaptureClicked() {
        // Feedback tátil/visual imediato, antes de qualquer processamento —
        // a sensação de "cliquei e algo aconteceu" tem que ser instantânea.
        pulseButton()
        flashScreen()

        val img = synchronized(imageLock) { latestImage }
        if (img == null) {
            showStatusBriefly("Carregando câmera…")
            return
        }

        // Copia os dados do frame ANTES de qualquer coisa assíncrona,
        // porque o Image pode ser reciclado pelo próximo frame a qualquer momento.
        val jpegBytes = try {
            yuv420888ToJpegBytes(img, quality = 100)
        } catch (e: Exception) {
            runOnUiThread { showStatusBriefly("Erro ao processar foto") }
            return
        }

        val rotation = computeJpegOrientation()
        val file = saveJpegToFile(jpegBytes, rotation)

        runOnUiThread { showStatusBriefly("Salvo · ${file.name}") }
    }

    /** Pisca a tela em branco por uma fração de segundo, tipo obturador. */
    private fun flashScreen() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.85f
        flashOverlay.animate().alpha(0f).setDuration(180).start()
    }

    /** Dá um pequeno "encolhe e volta" no botão pra confirmar o toque. */
    private fun pulseButton() {
        captureButton.animate().cancel()
        captureButton.scaleX = 0.88f
        captureButton.scaleY = 0.88f
        captureButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    /** Calcula a orientação EXIF correta com base na orientação do sensor. */
    private fun computeJpegOrientation(): Int {
        // Assumindo device em portrait (travamos screenOrientation="portrait" no manifest).
        val deviceOrientation = 90
        val rotation = (sensorOrientation - deviceOrientation + 360) % 360
        return when (rotation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun saveJpegToFile(jpegBytes: ByteArray, exifOrientation: Int): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(jpegBytes) }

        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            exif.saveAttributes()
        } catch (e: Exception) {
            // Se falhar o EXIF, o JPEG em si já está salvo — não é crítico.
        }
        return file
    }

    /**
     * Converte um Image em YUV_420_888 (layout tipo NV21) para bytes JPEG,
     * usando YuvImage.compressToJpeg — sem passar por Bitmap intermediário.
     */
    private fun yuv420888ToJpegBytes(image: Image, quality: Int): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
        } catch (e: Exception) { }
    }
}
