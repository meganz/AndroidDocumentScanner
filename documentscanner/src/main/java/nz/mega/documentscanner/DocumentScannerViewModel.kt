package nz.mega.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import kotlinx.coroutines.launch
import nz.mega.documentscanner.data.Document
import nz.mega.documentscanner.data.Document.FileType
import nz.mega.documentscanner.data.Document.Quality
import nz.mega.documentscanner.data.Page
import nz.mega.documentscanner.openCV.ImageScanner
import nz.mega.documentscanner.utils.BitmapUtils
import nz.mega.documentscanner.utils.BitmapUtils.rotate
import nz.mega.documentscanner.utils.DocumentGenerator.generateJpg
import nz.mega.documentscanner.utils.DocumentGenerator.generatePdf
import nz.mega.documentscanner.utils.DocumentUtils.deletePage
import nz.mega.documentscanner.utils.DocumentUtils.deletePages
import nz.mega.documentscanner.utils.FileUtils
import nz.mega.documentscanner.utils.LiveDataUtils.notifyObserver
import nz.mega.documentscanner.utils.PageUtils
import nz.mega.documentscanner.utils.PageUtils.deleteCropMat
import nz.mega.documentscanner.utils.PageUtils.deleteTransformImage
import nz.mega.documentscanner.utils.PageUtils.getNewRotation
import org.opencv.core.MatOfPoint2f
import java.io.File

class DocumentScannerViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val document: MutableLiveData<Document> = MutableLiveData(Document())
    private val saveDestinations: MutableLiveData<Array<String>> = MutableLiveData()
    private val currentPagePosition: MutableLiveData<Int> = MutableLiveData(0)
    private val resultDocument: MutableLiveData<Uri?> = MutableLiveData()
    private val flashMode: MutableLiveData<Int> = MutableLiveData(FLASH_MODE_AUTO)
    private var retakePosition: Int = NO_POSITION

    fun getResultDocument(): LiveData<Uri?> =
        resultDocument

    fun getDocumentTitle(): LiveData<String?> =
        document.map { it.title }

    fun getDocumentQuality(): LiveData<Quality> =
        document.map { it.quality }

    fun getDocumentFileType(): LiveData<FileType> =
        document.map { it.fileType }

    fun getSaveDestinations(): LiveData<List<Pair<String, Boolean>>> =
        saveDestinations.map { destinations ->
            val currentSaveDestination = document.value?.saveDestination
            destinations.map { destination ->
                destination to (currentSaveDestination == destination)
            }
        }

    fun getSaveDestination(): String? =
        document.value?.saveDestination

    fun getDocumentPages(): LiveData<List<Page>> =
        document.map { it.pages.toList() }

    fun getCurrentPage(): LiveData<Page?> =
        currentPagePosition.map { document.value?.pages?.elementAtOrNull(it) }

    fun getCurrentPagePosition(): LiveData<Int> =
        currentPagePosition

    fun getPagesCount(): Int =
        document.value?.pages?.size ?: 0

    fun setCurrentPagePosition(position: Int) {
        currentPagePosition.value = position
    }

    fun setDocumentTitle(title: String?) {
        if (document.value?.title == title) return

        document.value?.title = title
        document.notifyObserver()
    }

    fun setDocumentFileType(fileType: FileType) {
        if (document.value?.fileType == fileType) return

        document.value?.fileType = fileType
        document.notifyObserver()
    }

    fun setDocumentQuality(quality: Quality) {
        if (document.value?.quality == quality) return

        document.value?.quality = quality
        document.notifyObserver()
    }

    fun setDocumentSaveDestination(saveDestination: String) {
        if (document.value?.saveDestination == saveDestination) return

        document.value?.saveDestination = saveDestination
        document.notifyObserver()
    }

    fun setSaveDestinations(destinations: Array<String>) {
        saveDestinations.value = destinations
        document.value?.saveDestination = destinations.firstOrNull()
    }

    fun setFlashMode(flashMode: Int) {
        this.flashMode.value = flashMode
    }

    fun getFlashMode(): LiveData<Int> =
        flashMode

    /**
     * Add page to the scan.
     *
     * @param context Activity/Fragment Context needed to create the image file
     * @param bitmap Bitmap image to add to the scan
     * @return LiveData that emits true when the action has finished successfully, false otherwise
     */
    fun addPage(context: Context, bitmap: Bitmap): LiveData<Boolean> {
        val operationResult = MutableLiveData<Boolean>()

        viewModelScope.launch {
            try {
                val originalImageFile = FileUtils.createImageFile(context, bitmap)
                var transformImageFile: File? = null
                var transformBitmap: Bitmap? = null

                val cropMat = ImageScanner.getCropPoints(bitmap)
                cropMat?.let {
                    transformBitmap = ImageScanner.getCroppedBitmap(bitmap, it)
                    transformImageFile = FileUtils.createImageFile(context, transformBitmap!!)
                }

                val page = Page(
                    originalImageUri = originalImageFile.toUri(),
                    transformImageUri = transformImageFile?.toUri(),
                    cropMat = cropMat
                )

                if (retakePosition == NO_POSITION) {
                    document.value?.pages?.add(page)
                } else {
                    document.value?.pages?.add(retakePosition, page)
                }
                // Reset retakePosition after the page is added to avoid retakePosition causes the add page is incorrect.
                retakePosition = NO_POSITION

                bitmap.recycle()
                transformBitmap?.recycle()
                document.notifyObserver()
                setCurrentPagePosition(document.value?.pages?.indexOf(page) ?: 0)
                updateDocumentFileType()
                operationResult.postValue(true)
            } catch (error: Exception) {
                Log.e(TAG, error.stackTraceToString())
                operationResult.postValue(false)
            }
        }

        return operationResult
    }

    /**
     * Rotate scan page 90 degrees clockwise.
     *
     * @param context Activity/Fragment Context needed to create the image file
     * @param position Page position to be rotated. Default value is the current position
     */
    fun rotatePage(context: Context, position: Int = currentPagePosition.value ?: 0) {
        viewModelScope.launch {
            document.value?.pages?.get(position)?.let { page ->
                val imageUri = page.transformImageUri ?: page.originalImageUri

                val transformBitmap = BitmapUtils.getBitmapFromUri(
                    imageUri = imageUri,
                    degreesToRotate = PageUtils.PAGE_ROTATION_DEGREES
                )
                val transformImageFile = FileUtils.createImageFile(context, transformBitmap)

                val updatedPage = page.copy(
                    transformImageUri = transformImageFile.toUri(),
                    rotation = page.getNewRotation()
                )

                page.deleteTransformImage()
                transformBitmap.recycle()
                document.value?.pages?.set(position, updatedPage)
                document.notifyObserver()
            }
        }
    }

    /**
     * Crop scan page with the provided MatOfPoint2f.
     *
     * @param context Activity/Fragment Context needed to create the image file
     * @param cropMat OpenCV MatOfPoint2f with the desired cropping
     * @param position Page position to be cropped. Default value is the current position
     */
    fun cropPage(
        context: Context,
        cropMat: MatOfPoint2f,
        position: Int = currentPagePosition.value ?: 0
    ) {
        viewModelScope.launch {
            document.value?.pages?.get(position)?.let { page ->
                if (page.cropMat == cropMat) {
                    return@let
                }

                val originalBitmap = BitmapUtils.getBitmapFromUri(imageUri = page.originalImageUri)
                val transformBitmap = ImageScanner.getCroppedBitmap(originalBitmap, cropMat).rotate(page.rotation)
                val transformImageFile = FileUtils.createImageFile(context, transformBitmap)

                val updatedPage = page.copy(
                    transformImageUri = transformImageFile.toUri(),
                    cropMat = cropMat
                )

                page.deleteCropMat()
                page.deleteTransformImage()
                transformBitmap.recycle()
                document.value?.pages?.set(position, updatedPage)
                document.notifyObserver()
            }
        }
    }

    /**
     * Retake scan page, only update the retakePage, don't delete the current page.
     *
     * @param position Page position to be retaken. Default value is the current position
     */
    fun retakePage(position: Int = currentPagePosition.value ?: 0) {
        retakePosition = position
    }

    /**
     * Delete the previous page if retaking the page
     */
    fun deletePageForRetake() {
        if (retakePosition != NO_POSITION){
            deletePage(retakePosition)
        }
    }

    /**
     * Delete scan page
     *
     * @param position Page position to be deleted. Default value is the current position
     */
    fun deletePage(position: Int = currentPagePosition.value ?: 0) {
        viewModelScope.launch {
            document.value?.deletePage(position)

            if (document.value?.pages?.size == 0) {
                resetDocument()
            } else {
                document.notifyObserver()
            }
        }
    }

    /**
     * Reset document by deleting all existing pages
     */
    fun resetDocument() {
        viewModelScope.launch {
            document.value?.deletePages()
            document.value = Document(saveDestination = saveDestinations.value?.firstOrNull())
        }
    }

    /**
     * Reset current document and close the scanner
     */
    fun discardScan() {
        resetDocument()
        resultDocument.value = null
    }

    /**
     * Generate document file from the current scan.
     *
     * @param context Activity/Fragment Context needed to create the document file
     * @return LiveData that emits true when the action has finished successfully, false otherwise
     */
    fun generateDocument(context: Context): MutableLiveData<Boolean> {
        val operationResult = MutableLiveData<Boolean>()

        viewModelScope.launch {
            try {
                val document = requireNotNull(document.value)
                resultDocument.value = when (document.fileType) {
                    FileType.JPG -> document.generateJpg(context)
                    FileType.PDF -> document.generatePdf(context)
                }
                operationResult.postValue(true)
            } catch (error: Exception) {
                Log.e(TAG, error.stackTraceToString())
                operationResult.postValue(false)
            }
        }

        return operationResult
    }

    private fun updateDocumentFileType() {
        if ((document.value?.pages?.size ?: 0) > 1) {
            document.value?.fileType = FileType.PDF
        }
    }
}
