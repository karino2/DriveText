package com.github.harukawa.drivetext

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.coroutines.CoroutineContext
import android.preference.PreferenceManager

class MainActivity : AppCompatActivity(), CoroutineScope {
    lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    val recyclerView by lazy {
        findViewById<RecyclerView>(R.id.recycler_view)
    }

    private val TAG = "MainActivity"

    companion object {
        private const val REQUEST_GET_DATA = 0
    }

    val entryAdapter = EntryAdapter(this)

    val database by lazy { DatabaseHolder(this) }
    val SELECT_FIELDS = arrayOf("_id", "FILE_NAME")
    val ORDER_SENTENCE = "_id DESC"

    private fun queryCursor(): Cursor {
        return database.query(DatabaseHolder.ENTRY_TABLE_NAME) {
            select(*SELECT_FIELDS)
            order(ORDER_SENTENCE)
        }
    }

    override fun onStart() {
        job = Job()
        super.onStart()
        launch {
            val query = withContext(Dispatchers.IO) {
                queryCursor()
            }
            entryAdapter.swapCursor(query)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView.adapter = entryAdapter

        recyclerView.setHasFixedSize(true)
        recyclerView.layoutManager = LinearLayoutManager(this)
        setupActionMode()
    }

    fun deleteLocalFiles(ids: List<Long>) {
        ids.forEach {
            val localFile = database.getLocalFile(it)
            deleteFile(localFile.fileName)
        }
    }

    private fun setupActionMode() {
        entryAdapter.actionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val inflater = mode.menuInflater
                inflater.inflate(R.menu.delete_context_menu, menu)
                entryAdapter.isSelecting = true
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.delete_item -> {
                        launch {
                            val newCursor = withContext(Dispatchers.IO) {
                                deleteLocalFiles(entryAdapter.selectedIds)
                                database.deleteEntries(entryAdapter.selectedIds)
                                queryCursor()
                            }
                            entryAdapter.swapCursor(newCursor)
                            entryAdapter.isSelecting = false
                            mode.finish()
                        }

                    }
                }
                return false
            }


            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                entryAdapter.isSelecting = false
                entryAdapter.notifyDataSetChanged()
            }

        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_new -> {
            val intent = Intent(this, TextEditorActivity::class.java)
            startActivity(intent)
            true
        }
        R.id.action_update -> {
            updateFile()
            true
        }
        R.id.action_setting -> {
            val intent = Intent(this,SettingsActivity::class.java)
            startActivity(intent)
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    fun setDriveConnect(data: Intent): Drive {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val googleAccount : GoogleSignInAccount = task.result!!
        // Use the authenticated account to sign in to the Drive service.
        val credential : GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
            this, listOf(DriveScopes.DRIVE)
        )
        credential.selectedAccount = googleAccount.account
        val googleDriveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            JacksonFactory.getDefaultInstance(),
            credential)
            .setApplicationName(getString(R.string.app_name))
            .build()
        return googleDriveService
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when(requestCode) {
            REQUEST_GET_DATA -> {
                //https://developers.google.com/api-client-library/java/google-api-java-client/media-upload
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val drive = setDriveConnect(data)
                    updateFileAndDb(drive)
                } else {
                    Log.d("failure connect","faile upload file")
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    fun updateFile() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE)).requestEmail()
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        startActivityForResult(client.signInIntent, REQUEST_GET_DATA)
    }


    // After rewrite to DriveConnecter
    fun updateFileAndDb(googleDriveService: Drive) {
        var isUpdate = false
        var isDownload = false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val parentId = prefs.getString("drive_parent_path", "")

        launch(Dispatchers.Default) {
            val result = googleDriveService.files().list().apply {
                spaces = "drive"
                q = "trashed=false"
                fields = "nextPageToken, files(id, name, modifiedTime, parents)"
                this.pageToken = pageToken
            }.execute()
            for(file in result.files) {

                // Download only files in the specific folder
                val parents = file.parents ?: mutableListOf()
                var isParents = false
                for(par in parents){
                    if(par == parentId) isParents = true
                }
                if(!isParents) continue

                isUpdate = false
                isDownload = false

                val (db_name, _) = database.getData(file.id)
                if(db_name == "") isDownload = true
                if(!isDownload) {
                    isUpdate = checkData(file.id, Date(file.modifiedTime.value))
                }
                if(isUpdate || isDownload) {
                    // Get file data from drive
                    val driveName = file.name
                    val fileName = file.id + "_" + driveName
                    downLoadFile(googleDriveService, file.id.toString(), fileName)

                    if(isUpdate) {
                        val dbId = database.getId(file.name, file.id)
                        database.updateEntry(dbId, driveName, Date(), Date(file.modifiedTime.value))
                    }
                    if(isDownload) {
                        database.insertEntry(driveName, file.id, Date(), Date(file.modifiedTime.value))
                    }
                }
            }
            // finish download and update
        }
    }

    fun checkData(id: String, driveDate : Date) : Boolean {
        val (_, date) = database.getData(id)
        val dbDate = Date(date)
        // When the update time of drive is the latest
        if(driveDate.compareTo(dbDate) == 1) {
            return true
        } else {
            return false
        }
    }

    suspend fun downLoadFile(googleDriveService: Drive, id : String, name : String) {

        // Download file refers to https://developers.google.com/drive/api/v3/manage-downloads
        val outputStream: ByteArrayOutputStream = ByteArrayOutputStream()
        googleDriveService.files().get(id).executeMediaAndDownloadTo(outputStream)
        openFileOutput(name, Context.MODE_PRIVATE).use{
            it.write(outputStream.toByteArray())
        }
        outputStream.close()
    }

    override fun onStop() {
        super.onStop()
        job.cancel()
    }

    override fun onDestroy() {
        entryAdapter.swapCursor(null)
        database.close()
        super.onDestroy()
    }

    class ViewHolder(v: View): RecyclerView.ViewHolder(v) {
        val textView: TextView = v.findViewById(R.id.textViewFile)
    }

    class EntryAdapter(val context: Context) :
        RecyclerView.Adapter<ViewHolder> () {

        var actionModeCallback : ActionMode.Callback? = null
        var isSelecting = false
        private var cursor: Cursor? = null

        val selectedIds = arrayListOf<Long>()

        //var dataSet : Array<String> = arrayOf("one","two")
        companion object {
            private val TAG = "entryAdapter"
        }

        fun swapCursor(newCursor: Cursor?) {
            cursor?.let { it.close() }
            cursor = newCursor
            newCursor?.let {
                notifyDataSetChanged()
            }
        }

        fun toggleSelect(item: View) {
            val id = item.tag as Long
            if(item.isActivated) {
                selectedIds.remove(id)
                item.isActivated = false
            } else {
                selectedIds.add(id)
                item.isActivated = true
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ViewHolder(inflater.inflate(R.layout.file_item, parent, false)).apply {
                itemView.setOnLongClickListener {view->
                    actionModeCallback?.let {
                        (view.context as AppCompatActivity).startSupportActionMode(it)
                        toggleSelect(view)
                        true
                    } ?: false
                }
                itemView.setOnClickListener {
                    if(isSelecting) {
                        it.setBackgroundResource(R.color.colorSelected)
                        toggleSelect(it)
                    } else {
                        editItem(it.tag as Long)
                    }
                }
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val curs = cursor
            if(curs == null)
                throw IllegalStateException("onViewViewHolder called when cursor is null. What's situation?")
            curs.moveToPosition(position)
            holder.itemView.setBackgroundResource(R.color.colorDefault)
            holder.textView.text = curs.getString(1)
            holder.itemView.tag = curs.getLong(0)
        }

        override fun getItemCount() = cursor?.count ?: 0

        override fun getItemId(position: Int): Long {
            return cursor?.let {
                it.moveToPosition(position)
                // I assume _id is  columnIndex 0. It's defacto.
                return it.getLong(0)
            } ?: 0
        }

        fun editItem(id: Long) {
            val intent = Intent(context, TextEditorActivity::class.java)
            intent.putExtra("DB_ID",id)
            context.startActivity(intent)
        }
    }
}