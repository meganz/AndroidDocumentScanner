package nz.mega.documentscanner

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.facebook.drawee.backends.pipeline.Fresco
import kotlinx.coroutines.launch
import nz.mega.documentscanner.databinding.ActivityDocumentScannerBinding
import nz.mega.documentscanner.openCV.ImageScanner
import nz.mega.documentscanner.utils.FileUtils
import nz.mega.documentscanner.utils.IntentUtils.extra
import nz.mega.documentscanner.utils.ViewUtils.hideKeyboard

class DocumentScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICKED_SAVE_DESTINATION = "EXTRA_PICKED_SAVE_DESTINATION"

        private const val TAG = "DocumentScannerActivity"
        private const val EXTRA_SAVE_DESTINATIONS = "EXTRA_SAVE_DESTINATIONS"

        @JvmStatic
        @JvmOverloads
        fun getIntent(context: Context, saveDestinations: Array<String>? = null): Intent =
            Intent(context, DocumentScannerActivity::class.java).apply {
                saveDestinations?.let { putExtra(EXTRA_SAVE_DESTINATIONS, it) }
            }
    }

    private lateinit var binding: ActivityDocumentScannerBinding

    private val viewModel: DocumentScannerViewModel by viewModels()
    private val saveDestinations: Array<String>? by extra(EXTRA_SAVE_DESTINATIONS)
    private val destinationChangedListener: NavController.OnDestinationChangedListener by lazy {
        NavController.OnDestinationChangedListener { _, _, _ -> currentFocus?.hideKeyboard() }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initOpenCV()
        initFresco()

        binding = ActivityDocumentScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupObservers()

        findNavController().addOnDestinationChangedListener(destinationChangedListener)
    }

    override fun onDestroy() {
        findNavController().removeOnDestinationChangedListener(destinationChangedListener)
        super.onDestroy()
    }

    private fun initOpenCV() {
        lifecycleScope.launch {
            ImageScanner.init()
            Log.d(TAG, "OpenCV initialized")
        }
    }

    private fun initFresco() {
        if (!Fresco.hasBeenInitialized()) {
            Fresco.initialize(this)
        }
    }

    private fun setupObservers() {
        viewModel.getResultDocument().observe(this, ::onResultDocument)

        if (!saveDestinations.isNullOrEmpty()) {
            viewModel.setSaveDestinations(saveDestinations!!)
        } else if (BuildConfig.DEBUG) {
            viewModel.setSaveDestinations(arrayOf("Cloud Drive", "Chat"))
        }
    }

    private fun onResultDocument(documentUri: Uri?) {
        when {
            documentUri == null -> {
                setResult(Activity.RESULT_CANCELED)
            }
            callingActivity != null -> {
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_PICKED_SAVE_DESTINATION, viewModel.getSaveDestination())
                    setDataAndType(documentUri, contentResolver.getType(documentUri))
                }
                setResult(Activity.RESULT_OK, resultIntent)
            }
            else -> {
                val providerAuthority = FileUtils.getProviderAuthority(this)
                val fileUri = FileProvider.getUriForFile(this, providerAuthority, documentUri.toFile())
                val fileMimeType = contentResolver.getType(fileUri)
                val fileTitle = viewModel.getDocumentTitle().value

                val shareIntent = ShareCompat.IntentBuilder.from(this)
                    .setType(fileMimeType)
                    .setChooserTitle(fileTitle)
                    .setStream(fileUri)
                    .createChooserIntent()
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply {
                        putExtra(EXTRA_PICKED_SAVE_DESTINATION, viewModel.getSaveDestination())
                    }

                startActivity(shareIntent)
            }
        }

        finish()
    }

    private fun findNavController(): NavController =
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
}
