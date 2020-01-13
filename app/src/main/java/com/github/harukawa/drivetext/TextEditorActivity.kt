package com.github.harukawa.drivetext

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpHeaders
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
import kotlinx.android.synthetic.main.activity_text_editor.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.*
import java.util.*

typealias DriveFile = com.google.api.services.drive.model.File

class TextEditorActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
        private const val REQUEST_UPLOAD = 1
        private const val REQUEST_UPDATE = 2
    }

    val database by lazy { DatabaseHolder(this) }

    var dbId : Long = -1L
    var fileId = ""
    var isSave = false

    private val editText: EditText by lazy {
        findViewById<TextView>(R.id.editText) as EditText
    }

    private val titleText: EditText by lazy {
        findViewById<TextView>(R.id.titleEditText) as EditText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val EXTENSION = prefs.getString("extension", ".txt")

        dbId = intent.getLongExtra("DB_ID",-1L)
        if(dbId != -1L) {
            isSave = true
            readFile()
        } else {
            editText.setText("")
            titleText.setText("name"+EXTENSION)
        }

        val account = GoogleSignIn.getLastSignedInAccount(this) ?: GoogleSignInAccount.createDefault()

        if(GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE))){
            Log.d("GoogleSign", "has Drive Scope")
        } else {
            Log.d("GoogleSign", "not have Drive Scope")
        }

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        getMenuInflater().inflate(R.menu.text_editor_menu, menu);
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_save -> {
            saveFile()
            true
        }
        R.id.action_sent -> {
            sentFile()
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("request Result", "requestCode : ${requestCode}")
        when(requestCode) {
            REQUEST_UPLOAD -> {
                //https://developers.google.com/api-client-library/java/google-api-java-client/media-upload
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d("file","resultCode == ${resultCode}")
                    val drive = setDriveConnect(data, this)
                    val (name, _, _) = database.getEntry(dbId)
                    val path = applicationContext.filesDir.path + "/_" + name

                    launch(Dispatchers.Default) {
                        uploadFile(drive, path, name)
                        val (id, date) = getFileIdAndDate(drive, name)
                        database.updateDriveEntry(dbId, name, id, driveDate = date)
                        Log.d("DriveUpload", "dbId:${dbId}, name:${name}, id:${id}")

                        renameFile("_" + name, id + "_" + name)
                        finish()
                    }
                } else {
                    Log.d("FailureDriveConnect","failed upload file")
                }
            }
            REQUEST_UPDATE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d("file","resultCode == ${resultCode}")
                    val drive = setDriveConnect(data, this)
                    val (name, id, _) = database.getEntry(dbId)
                    val path = applicationContext.filesDir.path + "/" + id + "_" + name

                    launch(Dispatchers.Default) {
                        updateFile(drive, path, name, id)
                        val (_, date) = getFileIdAndDate(drive, name, id)
                        database.updateDriveEntry(dbId, name, id, driveDate = date)
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
        val fileName = if(isSave) fileId + "_" + title else fileId + "_" + title

        openFileOutput(fileName, Context.MODE_PRIVATE).use {
            it.write(editText.text.toString().toByteArray())
        }

        val cuDate = Date()
        if(isSave) {
            database.updateEntry(dbId, title ,fileId, localDate = cuDate)
        } else {
            database.insertEntry(title, fileId, cuDate, Date(0))
        }
        isSave = true
        dbId = database.getId(title)
        Log.d("saveFile", "dbId:${dbId}, fileName:${titleEditText.text.toString()},Date:${cuDate} ")
    }

    fun sentFile() {
        saveFile()
        val (name, id, _) = database.getEntry(dbId)
        Log.d("DriveUpload", "dbid ${dbId}, name:${name}, id:${id}")
        val request = if(id == "") REQUEST_UPLOAD else REQUEST_UPDATE

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE)).requestEmail()
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        startActivityForResult(client.signInIntent, request)
    }

    fun readFile() {
        val (name, id, _) = database.getEntry(dbId)
        val fileName = id + "_" + name
        val input = this.openFileInput(fileName)
        val text = BufferedReader(InputStreamReader(input)).readText() ?: ""
        fileId = id
        isSave = true
        editText.setText(text)

        if(id != "") {
            titleText.setText(name)
        } else {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val EXTENSION = prefs.getString("extension", ".txt")
            titleText.setText(name+EXTENSION)
        }
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
        val type = "text/plain"
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val parentId = prefs.getString("drive_parent_path", "")

        val blobMd = FileContent(type, File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = fileName
        if(parentId != "") targetDriveFile.parents = arrayListOf(parentId)
        try {
            googleDriveService.files().create(targetDriveFile, blobMd)
                .setFields("id, mimeType, modifiedTime")
                .execute()
        }  catch (e: UserRecoverableAuthIOException) {
            Log.d("GoogleSign","Error first Lognin :${e.toString()}")
            val mIntent = e.intent
            startActivityForResult(mIntent, REQUEST_UPLOAD)
        }
    }

    suspend fun updateFile(googleDriveService: Drive, filePath: String, fileName: String, fileId: String) {
        // Uploading file refers to https://developers.google.com/drive/api/v3/manage-uploads
        val blobMd = FileContent("text/plain", File(filePath))
        val targetDriveFile = DriveFile()
        targetDriveFile.name = fileName
        Log.d("GoogleUpdateFile","name:${fileName}, id${fileId}")

        try {
            googleDriveService.files().update(fileId, targetDriveFile, blobMd)
                .execute()
        } catch (e: UserRecoverableAuthIOException) {
            Log.d("GoogleSign","Error first Lognin :${e.toString()}")
            val mIntent = e.intent
            startActivityForResult(mIntent, REQUEST_UPDATE)
        }
    }
}