package com.github.harukawa.drivetext

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.android.synthetic.main.activity_text_editor.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.*

class TextEditorActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
        private const val REQUEST_SIGN_IN = 1
        private const val REQUEST_UPLOAD = 2
        private const val REQUEST_DOWNLOAD = 3
        private const val REQUEST_UPDATE = 4
    }

    val database by lazy { DatabaseHolder(this) }

    var dbId : Long = -1L
    var fileId = ""
    var isSave = false
    val EXTENSION = ".txt"

    private val editText: EditText by lazy {
        findViewById<TextView>(R.id.editText) as EditText
    }

    private val titleText: EditText by lazy {
        findViewById<TextView>(R.id.titleEditText) as EditText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)
        editText.setText("")

        dbId = intent.getLongExtra("DB_ID",-1L)
        if(dbId != -1L) {
            isSave = true
            readFile()
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
                    val dc = DriveConnecter()
                    val drive = dc.setDriveConnect(data, this)

                    launch(Dispatchers.Default) {
                        val (name, _, _) = database.getEntry(dbId)
                        val path = applicationContext.filesDir.path + "/_" + name + EXTENSION
                        dc.uploadFile(drive, path, name)
                        val (id, date) = dc.getFileIdAndDate(drive, name + EXTENSION)
                        database.updateDriveEntry(dbId, name, id, driveDate = date)
                        Log.d("DriveUpload", "dbId:${dbId}, name:${name}, id:${id}")

                        val (aname, aid, _) = database.getEntry(dbId)
                        Log.d("DriveUpload", "dbId:${dbId}, name:${aname}, id:${aid}")

                        renameFile("_" + name + EXTENSION, id + "_" + name + EXTENSION)
                    }
                } else {
                    Log.d("FailureDriveConnect","failed upload file")
                }
            }
            REQUEST_DOWNLOAD -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d("file","resultCode == ${resultCode}")
                    val dc = DriveConnecter()
                    val drive = dc.setDriveConnect(data, this)

                    launch(Dispatchers.Default) {
                        val (name, id, _) = database.getEntry(dbId)
                        dc.downLoadFile(drive, id, name,this@TextEditorActivity)
                    }
                } else {
                    Log.d("FailureDriveConnect","failed download")
                }
            }
            REQUEST_UPDATE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d("file","resultCode == ${resultCode}")
                    val dc = DriveConnecter()
                    val drive = dc.setDriveConnect(data, this)

                    launch(Dispatchers.Default) {
                        val (name, id, _) = database.getEntry(dbId)
                        val path = applicationContext.filesDir.path + "/_" + name + EXTENSION
                        dc.updateFile(drive, path, name, id)
                        val (_, date) = dc.getFileIdAndDate(drive, name + EXTENSION, id)
                        database.updateDriveEntry(dbId, name, id, driveDate = date)
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
        val fileName = fileId + "_" + title + EXTENSION
        openFileOutput(fileName, Context.MODE_APPEND).use {
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
        //val driveDate = database.getDbDate(dbId)
        //val date = Date(0).time.toLong()
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
        val fileName = id + "_" + name + EXTENSION
        val input = this.openFileInput(fileName)
        val text = BufferedReader(InputStreamReader(input)).readLine() ?: ""
        editText.setText(text)
        titleText.setText(name)
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
}