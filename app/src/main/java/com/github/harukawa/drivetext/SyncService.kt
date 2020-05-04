package com.github.harukawa.drivetext

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.FileContent
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.*

class SyncService : IntentService("Sync"), CoroutineScope by MainScope() {

    private val TAG = "SyncService"

    private var isNotLastTimeSync = false

    lateinit var googleDriveService: Drive

    var isComplete = false

    val database by lazy { DatabaseHolder(this) }
    val cmdTable by lazy { CommandTableHolder(this)}
    val parentId by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString("drive_parent_path", "")
    }

    override fun onHandleIntent(intent: Intent?) {
        isComplete = false
        googleDriveService = MainActivity.googleDriveService
        isNotLastTimeSync = false
        launch{
            run()
            isComplete = true
            val broadcastIntent = Intent()
            broadcastIntent.action = "FINISH_SYNC"
            baseContext.sendBroadcast(broadcastIntent)
        }
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

    fun setGetFileInfo(comPar: CommunicationParameters) {
        cmdTable.insertGetFileInfo(comPar)
    }

    suspend fun run() {
        try {
            var res = cmdTable.getPendingList()
            var q = ""
            var spaces = ""
            var fields = ""

            while(res.isNotEmpty()) {
                var current = res.get(0)


                Log.d(TAG, "Run Command ${current.cmd}")
                when (current.cmd) {
                    CommandTableHolder.CMD_ID_SYNC_UPLOAD -> {
                        val (fileName, filePath) = cmdTable.getUploadInfo(current.id)
                        uploadFile(filePath, fileName)
                        cmdTable.deleteEntries(current.id)
                    }
                    CommandTableHolder.CMD_ID_SYNC_UPDATE -> {
                        val (id, fileName, filePath) = cmdTable.getUpdateInfo(current.id)
                        val dbId = database.getId(fileName, id)
                        val localFile = database.getLocalFile(dbId)
                        updateFile(filePath,localFile)
                        cmdTable.deleteEntries(current.id)

                    }
                    CommandTableHolder.CMD_ID_SYNC_DOWNLOAD -> {
                        val (id, fileName) = cmdTable.getDownloadInfo(current.id)
                        downLoadFile(id, fileName)
                        cmdTable.deleteEntries(current.id)

                    }
                    CommandTableHolder.CMD_ID_SYNC_DRIVE_INFO -> {
                        /*
                        If the command for the previous synchronization is listed,
                        execute the previous command and then perform synchronization.
                         */
                        if(cmdTable.getPendingList().count() > 1){
                            isNotLastTimeSync = true
                            val (_q, _spaces, _fields) = cmdTable.getDriveInfo(current.id)
                            q = _q
                            spaces = _spaces
                            fields = _fields
                        } else {
                            val (q, spaces, fields) = cmdTable.getDriveInfo(current.id)
                            startSync(q, spaces, fields)
                        }
                        cmdTable.deleteEntries(current.id)
                    }
                    CommandTableHolder.CMD_ID_SYNC_DRIVE_FILE_INFO -> {
                        val comPar = cmdTable.getDriveFileInfo(current.id)
                        registFileData(comPar)
                        cmdTable.deleteEntries(current.id)
                    }
                    else -> {

                    }
                }
                res = cmdTable.getPendingList()
                if(res.isEmpty() and isNotLastTimeSync){
                    isNotLastTimeSync = false
                    cmdTable.insertGetDriveInfo(q, spaces, fields)
                }
            }
        }catch (e: Exception){
            Log.d(TAG, "Error run ${e.stackTrace}, ${e.message}, ${e.cause}")
        }
    }

    suspend fun startSync(param_q:String, param_spaces: String, param_fields: String) {
        val result = ArrayList<File>()
        withContext(Dispatchers.IO) {
            // https://developers.google.com/drive/api/v2/reference/files/list
            try {
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
                } while (request.pageToken != null && request.pageToken.isNotEmpty())
            } catch (e: UserRecoverableAuthIOException) {
                // Only the first time an error appears, so take out the intent and upload it again.
                Log.d("GoogleSign", "Error first Lognin :${e.toString()}")
            } catch(e: Exception) {
                Log.d(TAG, "Error Connect Drive" + e)
            }
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

    suspend fun registFileData(comPar : CommunicationParameters) {
        val localFile = LocalFile(comPar.fileName, "", 0L)
        var fileDate: DateTime = DateTime(0)
        var fileId = if(comPar.driveId != "") comPar.driveId else ""

        var result = FileList()

        withContext(Dispatchers.IO) {
            try {
                result = googleDriveService.files().list().apply {
                    q = "mimeType='text/plain'"
                    spaces = "drive"
                    fields = "nextPageToken, files(id, name, modifiedTime)"
                    this.pageToken = pageToken
                }.execute()
            } catch (e: UserRecoverableAuthIOException) {
                // Only the first time an error appears, so take out the intent and upload it again.
                Log.d("GoogleSign", "Error first Lognin :${e.toString()}")

            } catch (e : Exception) {
                Log.d(TAG, "Error Connect Drive " + e)
            }
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

    suspend fun downLoadFile(id : String, name : String) {
        Log.d(TAG, "start downLoadFile")
        Log.d(TAG, "id: ${id}")
        Log.d(TAG, "name : ${name}")
        withContext(Dispatchers.IO) {
            try {
                // Download file refers to https://developers.google.com/drive/api/v3/manage-downloads
                val outputStream: ByteArrayOutputStream = ByteArrayOutputStream()
                googleDriveService.files().get(id).executeMediaAndDownloadTo(outputStream)
                Log.d(TAG, "end googleDriveService.files().get(id)")
                this@SyncService.openFileOutput("_" + name, Context.MODE_PRIVATE).use {
                    it.write(outputStream.toByteArray())
                }
                Log.d(TAG, "end openFileOutput")
                outputStream.close()
            } catch (e: UserRecoverableAuthIOException) {
                // Only the first time an error appears, so take out the intent and upload it again.
                Log.d("GoogleSign", "Error first Lognin :${e.toString()}")
            } catch (e : Exception) {
                Log.d(TAG, "Error Connect Drive " + e)
            }
        }

        val comPar = CommunicationParameters(id, name,"",
            "mimeType='text/plain'", "drive",
            "","nextPageToken, files(id, name, modifiedTime)")
        setGetFileInfo(comPar)
    }


    suspend fun uploadFile(filePath : String, fileName: String) {
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
            } catch (e : Exception) {
                Log.d(TAG, "Error Connect Drive " + e)
            }
        }

        val comPar = CommunicationParameters("",fileName,"", "mimeType='text/plain'",
            "drive", "","nextPageToken, files(id, name, modifiedTime)")
        setGetFileInfo(comPar)
    }

    suspend fun updateFile(filePath: String, localFile: LocalFile) {
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
            } catch (e : Exception) {
                Log.d(TAG, "Error Connect Drive " + e)
            }
        }

        val comPar = CommunicationParameters(localFile.fileId, localFile.name,"",
            "mimeType='text/plain'", "drive",
            "","nextPageToken, files(id, name, modifiedTime)")
        setGetFileInfo(comPar)
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