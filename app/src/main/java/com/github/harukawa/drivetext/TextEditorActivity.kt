package com.github.harukawa.drivetext

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import io.github.karino2.listtextview.ListTextView
import kotlinx.android.synthetic.main.activity_text_editor.*
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

typealias DriveFile = com.google.api.services.drive.model.File

class TextEditorActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
        private const val REQUEST_UPLOAD = 1
        private const val REQUEST_UPDATE = 2
        private const val REQUEST_EDIT_CELL_CODE=3
    }

    val database by lazy { DatabaseHolder(this) }

    var dbId : Long = -1L
    var fileId = ""
    var isSave = false

    private val listTextView: ListTextView by lazy {
        findViewById<ListTextView>(R.id.listTextView).apply {
            this.editActivityRequestCode = REQUEST_EDIT_CELL_CODE
            this.owner = this@TextEditorActivity
            this.onDatasetChangedListener = { saveFile() }
        }
    }

    private val titleText: EditText by lazy {
        findViewById<TextView>(R.id.titleEditText) as EditText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)

        dbId = intent.getLongExtra("DB_ID",-1L)
        if(dbId != -1L) {
            isSave = true
            readFile()
        } else {
            listTextView.text = ""
            val tsf = SimpleDateFormat("yyyy-MM-dd-HHmmss")
            titleText.setText(tsf.format(Date()) + ".txt")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        getMenuInflater().inflate(R.menu.text_editor_menu, menu);
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_sent -> {
            sentFile()
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        data?.let {
            if(listTextView.handleOnActivityResult(requestCode, resultCode, it)) {
                return
            }
        }

        when(requestCode) {
            REQUEST_UPLOAD -> {
                //https://developers.google.com/api-client-library/java/google-api-java-client/media-upload
                if (resultCode == Activity.RESULT_OK && data != null) {
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


                    val drive = setDriveConnect(data, this)

                    // Get local file information to upload
                    val localFile = database.getLocalFile(dbId)
                    val path = applicationContext.filesDir.path + "/_" + localFile.name

                    launch {
                        uploadFile(drive, path, localFile.name)

                        // Get GoogleDriveID of uploaded file and upload time
                        val (id, date) = getFileIdAndDate(drive, localFile)

                        // Update database information
                        val newLocalFile = LocalFile(localFile.name, id, 0L)
                        database.updateDriveEntry(dbId, newLocalFile, driveDate = date)

                        // Add GoogleDrive ID to local file name to check if uploading or not.
                        renameFile("_" + localFile.name, id + "_" + localFile.name)
                        finish()
                    }
                } else {
                    Log.d("FailureDriveConnect","failed upload file")
                }
            }
            REQUEST_UPDATE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    /*
                    Overwrite the file existing in GoogleDrive to the local file.
                    Unlike upload, it also uses id.
                    Since the id is not changed,
                    Only the update time is obtained from GoogleDrive.
                     */

                    val drive = setDriveConnect(data, this)
                    val localFile = database.getLocalFile(dbId)
                    val path = applicationContext.filesDir.path + "/" + localFile.fileName

                    launch {
                        updateFile(drive, path, localFile)

                        // Get upload time
                        val (_, date) = getFileIdAndDate(drive, localFile)

                        // Update database information
                        database.updateDriveEntry(dbId, localFile, driveDate = date)
                        finish()
                    }
                } else {
                    Log.d("FailureDriveConnect","failed update file")
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    fun saveFile() {
        val title = titleEditText.text.toString()
        val fileName = fileId + "_" + title

        openFileOutput(fileName, Context.MODE_PRIVATE).use {
            it.write(listTextView.text.toByteArray())
        }

        val cuDate = Date()
        if(isSave) {
            database.updateEntry(dbId, title ,fileId, localDate = cuDate)
        } else {
            database.insertEntry(title, fileId, cuDate, Date(0))
        }
        isSave = true
        dbId = database.getId(title)
    }

    fun sentFile() {
        saveFile()
        val localFile = database.getLocalFile(dbId)
        val request = if(localFile.fileId == "") REQUEST_UPLOAD else REQUEST_UPDATE

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE)).requestEmail()
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        startActivityForResult(client.signInIntent, request)
    }

    fun readFile() {
        val localFile = database.getLocalFile(dbId)
        val fileName = localFile.fileName
        val input = this.openFileInput(fileName)
        val text = BufferedReader(InputStreamReader(input)).readText() ?: ""
        fileId = localFile.fileId
        isSave = true
        listTextView.text = text

        titleText.setText(localFile.name)

        input.close()
    }

    fun renameFile(old : String, new: String) {
        val s = this.filesDir
        val from = File(s, old)
        val to = File(s, new)
        if(from.exists()) {
            from.renameTo(to)
        }
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

    suspend fun getFileIdAndDate(googleDriveService: Drive, localFile: LocalFile) : Pair<String, Date> {
        val pageToken : String? = null
        var fileDate: DateTime = DateTime(0)
        var fileId = ""
        withContext(Dispatchers.IO) {


            val result = googleDriveService.files().list().apply {
                q = "mimeType='text/plain'"
                spaces = "drive"
                fields = "nextPageToken, files(id, name, modifiedTime)"
                this.pageToken = pageToken
            }.execute()
            for (file in result.files) {
                if (localFile.isMatch(file)) {
                    fileId = file.id
                    fileDate = file.modifiedTime
                }
            }
        }
        return Pair(fileId, Date(fileDate.value))
    }

    suspend fun uploadFile(googleDriveService: Drive, filePath : String, fileName: String) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        // To make FileContent with file's URI, the pdf file is saved as a temp file.
        val type = "text/plain"

        // If you have set the Google Drive folder ID, get that ID.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val parentId = prefs.getString("drive_parent_path", "")

        // Upload file settings.
        val blobMd = FileContent(type, File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = fileName
        if(parentId != "") targetDriveFile.parents = arrayListOf(parentId)

        withContext(Dispatchers.IO) {
            try {
                googleDriveService.files().create(targetDriveFile, blobMd)
                    .setFields("id, mimeType, modifiedTime")
                    .execute()
            } catch (e: UserRecoverableAuthIOException) {
                // Only the first time an error appears, so take out the intent and upload it again.
                Log.d("GoogleSign", "Error first Lognin :${e.toString()}")
                val mIntent = e.intent
                startActivityForResult(mIntent, REQUEST_UPLOAD)
            }
        }
    }

    suspend fun updateFile(googleDriveService: Drive, filePath: String, localFile: LocalFile) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        // Update file settings.
        val blobMd = FileContent("text/plain", File(filePath))
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
                startActivityForResult(mIntent, REQUEST_UPDATE)
            }
        }
    }
}