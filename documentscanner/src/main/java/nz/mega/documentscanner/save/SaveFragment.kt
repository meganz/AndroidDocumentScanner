package nz.mega.documentscanner.save

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import nz.mega.documentscanner.DocumentScannerViewModel
import nz.mega.documentscanner.R
import nz.mega.documentscanner.data.Document
import nz.mega.documentscanner.databinding.FragmentSaveBinding
import nz.mega.documentscanner.databinding.ItemDestinationBinding
import nz.mega.documentscanner.utils.FileUtils.FILE_NAME_PATTERN
import nz.mega.documentscanner.utils.ViewUtils.selectAllCharacters

class SaveFragment : Fragment() {

    companion object {
        private const val TAG = "SaveFragment"
        private const val INVALID_CHARACTERS = "\" * / : < > ? \\ |"
    }

    private lateinit var binding: FragmentSaveBinding
    private var fileNameWithoutSuffix = ""

    private val viewModel: DocumentScannerViewModel by activityViewModels()
    private val progressDialog: AlertDialog by lazy {
        MaterialAlertDialogBuilder(requireContext())
            .setView(R.layout.dialog_progress)
            .setMessage(
                getString(
                    R.string.scan_dialog_progress,
                    viewModel.getDocument().value?.fileType?.title
                        ?: Document.FileType.PDF.title
                )
            )
            .setCancelable(false)
            .show()
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            checkNameWhetherInvalidAndPopBackStack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupView()
        setupObservers()
        activity?.onBackPressedDispatcher?.addCallback(onBackPressedCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onBackPressedCallback.remove()
    }

    private fun setupView() {
        binding.editFileName.doAfterTextChanged { editable ->
            viewModel.setDocumentTitle(editable?.toString())
        }

        binding.editFileName.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                when (binding.inputFileName.error) {
                    getString(R.string.scan_incorrect_name) -> {
                        showSnackbar(R.string.scan_snackbar_incorrect_name)
                    }

                    getString(R.string.scan_invalid_characters) -> {
                        showSnackbar(R.string.scan_snackbar_invalid_characters)
                    }

                    else -> {
                        view.clearFocus()
                    }
                }
            }
            false
        }

        binding.editFileName.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            // When editFileName lost focus, update UI and file name
            binding.imgRename.isVisible = !hasFocus
            binding.fileName.isVisible = !hasFocus
            binding.inputFileName.isVisible = hasFocus
            if (!hasFocus) {
                updateFileName()
            }
        }

        binding.imgRename.setOnClickListener { binding.editFileName.selectAllCharacters() }

        binding.chipGroupFileType.setOnCheckedChangeListener { _, checkedId ->
            val fileType = when (checkedId) {
                R.id.chip_file_type_pdf -> Document.FileType.PDF
                R.id.chip_file_type_jpg -> Document.FileType.JPG
                else -> error("Unrecognized document file type")
            }

            viewModel.setDocumentFileType(fileType)
            checkAndClearFocusForEditFileName()
        }

        binding.chipGroupQuality.setOnCheckedChangeListener { _, checkedId ->
            val quality = when (checkedId) {
                R.id.chip_quality_low -> Document.Quality.LOW
                R.id.chip_quality_medium -> Document.Quality.MEDIUM
                R.id.chip_quality_high -> Document.Quality.HIGH
                else -> error("Unrecognized document quality")
            }

            viewModel.setDocumentQuality(quality)
            checkAndClearFocusForEditFileName()
        }

        binding.chipGroupDestinations.setOnCheckedChangeListener { group, checkedId ->
            group.children.firstOrNull { it.id == checkedId }
                ?.let { child ->
                    val destination = (child as Chip).text.toString()
                    viewModel.setDocumentSaveDestination(destination)
                }

            checkAndClearFocusForEditFileName()
        }

        binding.fileName.setOnClickListener { binding.editFileName.selectAllCharacters() }
        binding.groupFileType.isVisible = viewModel.getPagesCount() == 1
        binding.btnBack.setOnClickListener { checkNameWhetherInvalidAndPopBackStack() }
        binding.btnSave.setOnClickListener { createDocument() }
    }

    private fun setupObservers() {
        viewModel.getSaveDestinations().observe(viewLifecycleOwner, ::showSaveDestinations)
        viewModel.getDocumentFileType().observe(viewLifecycleOwner, ::showDocumentFileType)
        viewModel.getDocumentTitle().observe(viewLifecycleOwner, ::showDocumentTitle)
        viewModel.getDocumentQuality().observe(viewLifecycleOwner, ::showDocumentQuality)
    }

    private fun showSaveDestinations(destinations: List<Pair<String, Boolean>>) {
        binding.chipGroupDestinations.removeAllViews()
        binding.groupDestination.isVisible = destinations.isNotEmpty()

        destinations.forEach { destination ->
            val chip = ItemDestinationBinding.inflate(
                layoutInflater,
                binding.chipGroupDestinations,
                false
            ).root
            chip.text = destination.first

            binding.chipGroupDestinations.addView(chip)
            if (destination.second) {
                binding.chipGroupDestinations.check(chip.id)
            }
        }
    }

    private fun showDocumentTitle(title: String?) {
        when {
            title.isNullOrBlank() -> {
                binding.inputFileName.error = getString(R.string.scan_incorrect_name)
            }

            FILE_NAME_PATTERN.toRegex().containsMatchIn(title) -> {
                binding.inputFileName.error = getString(R.string.scan_invalid_characters)
            }

            title != binding.editFileName.text.toString() -> {
                binding.editFileName.setText(title)
                binding.inputFileName.error = null
                updateFileName()
            }

            else -> {
                binding.inputFileName.error = null
            }
        }
    }

    private fun showDocumentFileType(fileType: Document.FileType) {
        val chipResId: Int
        val imageResId: Int

        when (fileType) {
            Document.FileType.PDF -> {
                chipResId = R.id.chip_file_type_pdf
                imageResId = R.drawable.ic_docscanner_pdf
            }

            Document.FileType.JPG -> {
                chipResId = R.id.chip_file_type_jpg
                imageResId = R.drawable.ic_docscanner_jpeg
            }
        }
        binding.imgFileType.setImageResource(imageResId)
        if (binding.chipGroupFileType.checkedChipId != chipResId) {
            binding.chipGroupFileType.check(chipResId)
        }
        updateFileName()
    }

    private fun showDocumentQuality(quality: Document.Quality) {
        val chipResId = when (quality) {
            Document.Quality.LOW -> R.id.chip_quality_low
            Document.Quality.MEDIUM -> R.id.chip_quality_medium
            Document.Quality.HIGH -> R.id.chip_quality_high
        }

        if (binding.chipGroupQuality.checkedChipId != chipResId) {
            binding.chipGroupQuality.check(chipResId)
        }
    }

    private fun createDocument() {
        when (binding.inputFileName.error) {
            getString(R.string.scan_incorrect_name) -> {
                showSnackbar(R.string.scan_snackbar_incorrect_name)
            }

            getString(R.string.scan_invalid_characters) -> {
                showSnackbar(R.string.scan_snackbar_invalid_characters)
            }

            else -> {
                progressDialog.show()
                viewModel.generateDocument(requireContext()).observe(viewLifecycleOwner) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    private fun showSnackbar(@StringRes errorMessage: Int) {
        if (errorMessage == R.string.scan_snackbar_invalid_characters) {
            Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG)
                .setText(getString(R.string.scan_snackbar_invalid_characters, INVALID_CHARACTERS))
                .show()
        } else {
            Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFileName() {
        if (!binding.editFileName.text.isNullOrEmpty() && binding.inputFileName.error.isNullOrEmpty()) {
            fileNameWithoutSuffix = binding.editFileName.text.toString()
            binding.fileName.text =
                "$fileNameWithoutSuffix${viewModel.getDocument().value?.fileType?.suffix}"
        }
    }

    private fun checkAndClearFocusForEditFileName() {
        if (binding.inputFileName.error.isNullOrEmpty()) {
            binding.editFileName.clearFocus()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.editFileName.windowToken, 0)
        }
    }

    private fun checkNameWhetherInvalidAndPopBackStack() {
        // if the name is invalid, revert the name to the previous one.
        if (!binding.inputFileName.error.isNullOrEmpty()) {
            viewModel.setDocumentTitle(fileNameWithoutSuffix)
        }
        findNavController().popBackStack()
    }
}
