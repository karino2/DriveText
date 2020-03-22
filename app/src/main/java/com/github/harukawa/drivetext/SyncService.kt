package com.github.harukawa.drivetext

import android.app.Activity
import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.app.ActivityCompat.startActivityForResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.FileContent
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.coroutines.CoroutineContext

class SyncService : IntentService("Sync"), CoroutineScope by MainScope() {

    lateinit var job: Job

    // Binder given to clients
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService() = this@SyncService
    }

    override fun onHandleIntent(intent: Intent?) {
        launch{
            run()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    var isComplete = false

    val database by lazy { DatabaseHolder(this) }
    val cmdTable by lazy { CommandTableHolder(this)}
    val parentId by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString("drive_parent_path", "")
    }

    fun setUpload(fileName: String, filePath: String) {
        cmdTable.insertUpload(fileName, filePath)
    }

    fun setUpdate(id : String, fileName: String, filePath: String) {
        cmdTable.insertUpdate(id, fileName, filePath)
    }

    fun setDownload(id: String, fileName: String) {
        cmdTable.insertDownload(id, fileName)
    }

    fun setSync(q : String, spaces : String, fields : String) {
        cmdTable.insertGetDriveInfo(q, spaces, fields)
    }

    fun setGetFileInfo(comPar: CommunicationParameters) {
        cmdTable.insertGetFileInfo(comPar)
    }



    suspend fun run() {
        try {
            val googleDriveService = setDriveConnect(MainActivity.intent, this)

            var res = cmdTable.getPendingList()

            while(res.isNotEmpty()) {
                var current = res.get(0)


                Log.d("Sync", "succces ${current.cmd}")
                when (current.cmd) {
                    CommandTableHolder.CMD_ID_SYNC_UPLOAD -> {
                        val (fileName, filePath) = cmdTable.getUploadInfo(current.id)
                        uploadFile(googleDriveService, filePath, fileName)
                        cmdTable.deleteEntries(current.id)
                    }
                    CommandTableHolder.CMD_ID_SYNC_UPDATE -> {
                        val (id, fileName, filePath) = cmdTable.getUpdateInfo(current.id)
                        val dbId = database.getId(fileName, id)
                        val localFile = database.getLocalFile(dbId)
                        updateFile(googleDriveService, filePath,localFile)
                        cmdTable.deleteEntries(current.id)

                    }
                    CommandTableHolder.CMD_ID_SYNC_DOWNLOAD -> {
                        val (id, fileName) = cmdTable.getDownloadInfo(current.id)
                        downLoadFile(googleDriveService, id, fileName)
                        cmdTable.deleteEntries(current.id)

                    }
                    CommandTableHolder.CMD_ID_SYNC_DRIVE_INFO -> {
                        val (q, spaces, mimeType) = cmdTable.getDriveInfo(current.id)
                        startSync(googleDriveService, q, spaces, mimeType)
                        cmdTable.deleteEntries(current.id)
                    }
                    CommandTableHolder.CMD_ID_SYNC_DRIVE_FILE_INFO -> {
                        val comPar = cmdTable.getDriveFileInfo(current.id)
                        registFileData(googleDriveService, comPar)
                        cmdTable.deleteEntries(current.id)
                    }
                    else -> {

                    }
                }
                res = cmdTable.getPendingList()
            }
        }catch (e: Exception){
            val intent = Intent(this,MainActivity::class.java)
            intent.putExtra("Drive",true)
            startActivity(intent)
        }


    }

    suspend fun startSync(googleDriveService: Drive, param_q:String, param_spaces: String, param_fields: String) {
        val result = ArrayList<File>()
        withContext(Dispatchers.IO) {
            // https://developers.google.com/drive/api/v2/reference/files/list
            var request = googleDriveService.files().list().apply {
                spaces = param_spaces
                q = param_q
                fields = param_fields
                this.pageToken = pageToken
            }

            do {
                val files = request.execute()
                result.addAll(files.files)
                request.pageToken = files.nextPageToken
            }while(request.pageToken != null && request.pageToken.isNotEmpty())
        }

        var isUpdate : Boolean
        var isDownload : Boolean
        var isConflict : Boolean
        var isSameName : Boolean
        var isExistLocal : Boolean
        var isChangeName : Boolean

        /*
        Update or download is decided based on the date and time information of the file
        in the target folder obtained from Google Drive.
         */

        var driveIds = mutableListOf<String>()

        for(file in result){
            driveIds.add(file.id)
            isUpdate = false
            isDownload = false
            isConflict = false
            isSameName = false
            isExistLocal = false
            isChangeName = false


            val (db_name, _) = database.getData(file.id)
            Log.d("SYNC", "filename ${file.name}, fileId ${file.id} dbname: ${db_name}")
            if(db_name == "") {
                isDownload = true
                if(database.getId(file.name) != -1L) {
                    isSameName = true
                }
            } else {
                val compareDate: Int = checkDate(file.id, Date(file.modifiedTime.value))
                Log.d("compare sync", "name:${file.name} compare date ${compareDate}")
                if (compareDate < 0) {
                    Log.d("update", "syn update")
                    isUpdate = true
                } else if (compareDate > 0) {
                    isDownload = true
                    isExistLocal = true
                }

                if(db_name != file.name) {
                    isChangeName = true
                }

                val(local, dbDrive) = database.getLocalAndDriveData(file.id)
                Log.d("SYNC", "local:${Date(local)}, drive${Date(dbDrive)}, modified${Date(file.modifiedTime.value)}")
                if(Date(dbDrive).compareTo(Date(local)) < 0 &&
                    Date(dbDrive).compareTo(Date(file.modifiedTime.value)) < 0) {
                    isConflict = true
                    isDownload = false
                    isUpdate = false
                }
            }

            Log.d("SYNCData", "FILE Name:${file.name} id:${file.id}, isConflict:${isConflict.toString()} " +
                    "isDownload:${isDownload.toString()} isUpdate:${isUpdate} isChangeName:${isChangeName.toString()}" +
                    " isExistLocal:${isExistLocal.toString()} isSameName:${isSameName.toString()}")
            var driveFileName = ""
            val dbId = database.getId(db_name, file.id)
            if(isConflict) {
                //Remove google drive id to upload conflicting local files as new files
                database.deleteDriveIdEntry(dbId)
                val localFileName = if(isChangeName) db_name else addNameNumber(db_name)
                renameFile(file.id+ "_" + db_name, "_" + localFileName)
                database.updateEntry(dbId, localFileName, Date(file.modifiedTime.value), Date(file.modifiedTime.value))

                val id = database.getId(file.name)
                driveFileName = if(id != -1L) addNameNumber(file.name) else file.name

                database.insertEntry(driveFileName, file.id, Date(file.modifiedTime.value), Date(file.modifiedTime.value))
                setDownload(file.id, driveFileName)
            }

            if(isDownload) {
                // Get file data from drive
                val downloadFileName = if(isSameName && !isExistLocal) addNameNumber(file.name)
                else file.name
                setDownload(file.id, downloadFileName)


                if(!isExistLocal) {
                    database.insertEntry(downloadFileName, file.id, Date(file.modifiedTime.value), Date(file.modifiedTime.value))
                }
                if(isChangeName) {
                    // Delete old file
                    deleteLocalFile(file.id + "_" + db_name)
                    database.updateEntry(dbId, downloadFileName, Date(file.modifiedTime.value), Date(file.modifiedTime.value))
                }
            }

            if(isUpdate) {
                val path = this.filesDir.path + "/" + file.id + "_" + file.name
                setUpdate(file.id, file.name, path)
            }
        }

        /*
            Upload files not uploaded from local files to Drvie.
            For files deleted from Drive, delete Drive ID and leave it as unuploaded
         */

        val localFileList = database.getFileDataList();

        for(file in localFileList){
            if(file.second == "") {
                setUpload(file.first, this.filesDir.path + "/" + file.second + "_" + file.first)
            } else {
                if(!driveIds.contains(file.second)){
                    val dbId : Long = database.getDbId(file.second)
                    database.deleteDriveIdEntry(dbId)
                    renameFile(file.second + "_" + file.first, "_" + file.first)
                }
            }
        }

    }

    fun checkDate(id: String, driveDate : Date) : Int {
        val (_, date) = database.getData(id)
        val dbDate = Date(date)
        // When the update time of drive is the latest
        return driveDate.compareTo(dbDate)
    }

    suspend fun registFileData(googleDriveService: Drive, comPar : CommunicationParameters) {
        val localFile = LocalFile(comPar.fileName, "", 0L)
        var fileDate: DateTime = DateTime(0)
        var fileId = if(comPar.driveId != "") comPar.driveId else ""

        var result = FileList()

        withContext(Dispatchers.IO) {
            result = googleDriveService.files().list().apply {
                q = "mimeType='text/plain'"
                spaces = "drive"
                fields = "nextPageToken, files(id, name, modifiedTime)"
                this.pageToken = pageToken
            }.execute()
        }

        for (file in result.files) {
            if (localFile.isMatch(file)) {
                fileId = file.id
                fileDate = file.modifiedTime
            }
        }
        val date = Date(fileDate.value)
        val dbId = database.getId(localFile.name)
        database.updateEntry(dbId,  localFile.name, fileId, date, date)

        // For upload, add driveid to local file name
        if(comPar.driveId=="") {
            renameFile("_" + localFile.name, fileId + "_" + localFile.name)
        }
    }

    suspend fun downLoadFile(googleDriveService: Drive, id : String, name : String) {

        withContext(Dispatchers.IO) {
            // Download file refers to https://developers.google.com/drive/api/v3/manage-downloads
            val outputStream: ByteArrayOutputStream = ByteArrayOutputStream()
            googleDriveService.files().get(id).executeMediaAndDownloadTo(outputStream)
            this@SyncService.openFileOutput("_" + name, Context.MODE_PRIVATE).use {
                it.write(outputStream.toByteArray())
            }
            outputStream.close()
        }

        val comPar = CommunicationParameters(id, name,"",
            "mimeType='text/plain'", "drive",
            "","nextPageToken, files(id, name, modifiedTime)")
        setGetFileInfo(comPar)
    }


    suspend fun uploadFile(googleDriveService: Drive, filePath : String, fileName: String) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        // To make FileContent with file's URI, the pdf file is saved as a temp file.

        /*
            Upload a local file to GoogleDrive.
            Upload a file that has no files on GoogleDrive and exists only locally.
            Use the file name and path to upload GoogleDrive.
            The id is also used for updating.
            Since there is no id in the file before uploading,
            empty characters are registered in the database.
            Since id is specified by GoogleDrive,
            after uploading it is obtained based on the file name.
            After obtaining the id, use the id to determine the file.
        */

        val type = "text/plain"

        // Upload file settings.
        val blobMd = FileContent(type, java.io.File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = fileName
        if(parentId != "") targetDriveFile.parents = arrayListOf(parentId)

        Log.d("SyncUpload", "upload name:${fileName}, path:${filePath}")
        withContext(Dispatchers.IO) {
            try {
                googleDriveService.files().create(targetDriveFile, blobMd)
                    .setFields("id, mimeType, modifiedTime")
                    .execute()
            } catch (e: UserRecoverableAuthIOException) {
                // Only the first time an error appears, so take out the intent and upload it again.
                Log.d("GoogleSign", "Error first Lognin :${e.toString()}")
                val mIntent = e.intent
                //mActivity.startActivityForResult(mIntent, MainActivity.REQUEST_REDO)
            }
        }

        val comPar = CommunicationParameters("",fileName,"", "mimeType='text/plain'",
            "drive", "","nextPageToken, files(id, name, modifiedTime)")
        setGetFileInfo(comPar)
    }

    suspend fun updateFile(googleDriveService: Drive, filePath: String, localFile: LocalFile) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        // Update file settings.

        /*
            Overwrite the file existing in GoogleDrive to the local file.
            Unlike upload, it also uses id.
            Since the id is not changed,
            Only the update time is obtained from GoogleDrive.
         */
        val blobMd = FileContent("text/plain", java.io.File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = localFile.name

        withContext(Dispatchers.IO) {
            try {
                googleDriveService.files().update(localFile.fileId, targetDriveFile, blobMd)
                    .execute()
            } catch (e: UserRecoverableAuthIOException) {
                // Only the first time an error appears, so take out the intent and upload it again.
                Log.d("GoogleSign", "Error first Login :${e.toString()}")
                val mIntent = e.intent
                //mActivity.startActivityForResult(mIntent, MainActivity.REQUEST_REDO)
            }
        }

        val comPar = CommunicationParameters(localFile.fileId, localFile.name,"",
            "mimeType='text/plain'", "drive",
            "","nextPageToken, files(id, name, modifiedTime)")
        setGetFileInfo(comPar)
    }

    fun setDriveConnect(data: Intent, context: Context): Drive {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)

        val googleAccount : GoogleSignInAccount = task.result!!
        val credential : GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE, Scopes.DRIVE_APPFOLDER)
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

    fun renameFile(old : String, new: String) {
        val s = this.filesDir
        val from = java.io.File(s, old)
        val to = java.io.File(s, new)
        Log.d("Sync:rename", "from ${from.absolutePath}, to ${to.absolutePath}")
        if(from.exists()) {
            from.renameTo(to)
        }
    }

    fun deleteLocalFile(fileName : String) {
        val file = java.io.File(this.filesDir, fileName)
        file.delete()
    }

    fun addNameNumber(name : String) : String{
        val regex = "\\.".toRegex()
        var addName = regex.replace(name, "(1).")
        while(true) {
            var id = database.getId(addName)
            if(id == -1L){
                break
            }
            addName = regex.replace(name, "(1).")
        }
        return addName
    }
}