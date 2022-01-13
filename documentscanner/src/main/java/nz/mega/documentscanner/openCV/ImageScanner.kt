package nz.mega.documentscanner.openCV

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mega.documentscanner.BuildConfig
import nz.mega.documentscanner.openCV.OpenCvUtils.crop
import nz.mega.documentscanner.openCV.OpenCvUtils.yuvToRgbaMat
import nz.mega.documentscanner.utils.BitmapUtils.toMat
import org.opencv.core.MatOfPoint2f

object ImageScanner {

    /**
     * Initialise Image Scanner. This method should be called before the Image Scanner is going to
     * be used in order to initialise OpenCV library.
     *
     * @return true if it has been initialised successfully, false otherwise.
     */
    suspend fun init() {
        if (!isCpuCompatible()) {
            error("CPU is not compatible with ${BuildConfig.NDK_ABI_FILTERS.contentToString()}")
        }

        return withContext(Dispatchers.IO) {
            System.loadLibrary("opencv_java4")
        }
    }

    /**
     * Check if current CPU architecture is compatible with declared NDK ABI Filters.
     *
     * @return  true if the CPU is compatible, false otherwise.
     */
    fun isCpuCompatible(): Boolean =
        BuildConfig.NDK_ABI_FILTERS.contains(Build.SUPPORTED_ABIS.first())

    /**
     * Get crop points from a given Bitmap.
     *
     * @param bitmap Image to get crop points from
     * @return OpenCV MatOfPoint2f containing image crop points
     */
    suspend fun getCropPoints(bitmap: Bitmap): MatOfPoint2f? =
        withContext(Dispatchers.Default) {
            val bitmapMat = bitmap.toMat()
            CropDetector.detect(bitmapMat)
        }

    /**
     * Get a cropped bitmap given the original bitmap and provided crop points.
     *
     * @param bitmap Original Bitmap to be cropped
     * @param cropMat Crop points to crop the Bitmap
     * @return Cropped Bitmap
     */
    suspend fun getCroppedBitmap(bitmap: Bitmap, cropMat: MatOfPoint2f): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmapMat = bitmap.toMat()
            bitmapMat.crop(cropMat)
        }

    /**
     * Get crop lines to be drawn given an ImageProxy image, its container view width and height.
     *
     * @param imageProxy Multi-plane Android YUV 420 formatted image
     * @param maxWidth Container view width to calculate the lines
     * @param maxHeight Container view height to calculate the lines
     * @return Array of points to draw [x0 y0 x1 y1 x2 y2 ...]
     */
    suspend fun getCropLines(
        imageProxy: ImageProxy,
        maxWidth: Float,
        maxHeight: Float
    ): FloatArray? = withContext(Dispatchers.Default) {
        var resultArray: FloatArray? = null
        val imageMat = imageProxy.yuvToRgbaMat()
        val ratioX = maxWidth / imageMat.width()
        val ratioY = maxHeight / imageMat.height()
        val matOfPoints = CropDetector.detect(imageMat)

        if (matOfPoints != null) {
            val points = matOfPoints.toArray().map { point ->
                PointF(point.x.toFloat() * ratioX, point.y.toFloat() * ratioY)
            }

            resultArray = floatArrayOf(
                points[0].x,
                points[0].y,
                points[1].x,
                points[1].y,
                points[1].x,
                points[1].y,
                points[2].x,
                points[2].y,
                points[2].x,
                points[2].y,
                points[3].x,
                points[3].y,
                points[3].x,
                points[3].y,
                points[0].x,
                points[0].y,
            )
        }

        matOfPoints?.release()
        imageMat.release()

        resultArray
    }
}
