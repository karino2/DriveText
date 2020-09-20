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
import io.github.karino2.listtextview.ListTextView
import kotlinx.android.synthetic.main.activity_text_editor.*
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

typealias DriveFile = com.google.api.services.drive.model.File

class TextEditorActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        data?.let {
            if(listTextView.handleOnActivityResult(requestCode, resultCode, it)) {
                return
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

}