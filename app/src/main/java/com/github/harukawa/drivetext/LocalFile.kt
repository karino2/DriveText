package com.github.harukawa.drivetext

data class LocalFile(val name: String, val fileId: String, val localDate: Long) {
    val fileName : String
        get() = fileId + "_" + name

    fun isMatch(driveFile: com.google.api.services.drive.model.File) =
        driveFile.name == name && (fileId == "" || fileId == driveFile.id)
}