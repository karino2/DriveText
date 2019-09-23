package com.github.harukawa.drivetext

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.Scopes.DRIVE_APPFOLDER
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes.DRIVE_FILE
import kotlinx.coroutines.*
import java.io.*
import java.util.*

typealias DriveFile = com.google.api.services.drive.model.File

class DriveConnecter :  CoroutineScope by MainScope(){

    val EXTENSION = ".txt"

    fun setDriveConnect(data: Intent, context: Context): Drive {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val googleAccount : GoogleSignInAccount = task.result!!
        val credential : GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DRIVE_FILE, DRIVE_APPFOLDER)
        )
        credential.selectedAccount = googleAccount.account
        val googleDriveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            JacksonFactory.getDefaultInstance(),
            credential)
            .setApplicationName(context.getString(R.string.app_name))
            .build()
        return googleDriveService
    }

    suspend fun getFileIdAndDate(googleDriveService: Drive, name : String, id : String = "") : Pair<String, Date> {
        val pageToken : String? = null
        val result = googleDriveService.files().list().apply {
            q = "mimeType='text/plain'"
            spaces = "drive"
            fields = "nextPageToken, files(id, name, modifiedTime)"
            this.pageToken = pageToken
        }.execute()
        var fileDate : DateTime = DateTime(0)
        var fileId = ""
        for(file in result.files) {
            if(file.name == name && (id == "" || id == file.id)) {
                fileId = file.id
                fileDate = file.modifiedTime
            }
        }
        return Pair(fileId, Date(fileDate.value))
    }

    suspend fun uploadFile(googleDriveService: Drive, filePath : String, fileName: String) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        // To make FileContent with file's URI, the pdf file is saved as a temp file.
        val type = "text/plain" //application/vnd.google-apps.document,

        val blobMd = FileContent(type, File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = fileName + EXTENSION
        googleDriveService.files().create(targetDriveFile, blobMd)
            .setFields("id, mimeType, modifiedTime")
            .execute()
    }

    suspend fun downLoadFile(googleDriveService: Drive, id : String, name : String, context: Context) {

        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-downloads
        val outputStream: ByteArrayOutputStream = ByteArrayOutputStream()
        googleDriveService.files().get(id).executeMediaAndDownloadTo(outputStream)
        context.openFileOutput(name + EXTENSION, Context.MODE_PRIVATE).use{
            it.write(outputStream.toByteArray())
        }
        outputStream.close()
        val (_, date) = getFileIdAndDate(googleDriveService, name, id)
        Log.d("google download", "download file ${Date()}")
    }

    suspend fun updateFile(googleDriveService: Drive, filePath: String, fileName: String, fileId: String) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        // To make FileContent with file's URI, the pdf file is saved as a temp file.
        val blobMd = FileContent("text/plain", File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = fileName + EXTENSION
        Log.d("GoogleUpdateFile","name:${fileName}, id${fileId}")
        googleDriveService.files().update(fileId, targetDriveFile, blobMd)
            .execute()
    }
}