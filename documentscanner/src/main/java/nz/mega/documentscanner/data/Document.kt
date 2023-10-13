package nz.mega.documentscanner.data

import nz.mega.documentscanner.utils.FileUtils
import java.util.Calendar

data class Document constructor(
    var title: String? = String.format(FileUtils.FILE_NAME_FORMAT, Calendar.getInstance()),
    var fileType: FileType = FileType.PDF,
    var quality: Quality = Quality.MEDIUM,
    var saveDestination: String? = null,
    var pages: MutableList<Page> = mutableListOf()
) {

    enum class FileType(val suffix: String) { PDF("pdf"), JPG("jpg") }
    enum class Quality(val value: Int) { LOW(35), MEDIUM(50), HIGH(90) }
}
