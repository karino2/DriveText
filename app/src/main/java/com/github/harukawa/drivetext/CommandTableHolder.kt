package com.github.harukawa.drivetext

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.github.harukawa.drivetext.CommandTableHolder.Companion.CMD_ID_SYNC_DOWNLOAD
import com.github.harukawa.drivetext.CommandTableHolder.Companion.CMD_ID_SYNC_DRIVE_FILE_INFO
import com.github.harukawa.drivetext.CommandTableHolder.Companion.CMD_ID_SYNC_DRIVE_INFO
import com.github.harukawa.drivetext.CommandTableHolder.Companion.CMD_ID_SYNC_UPDATE
import com.github.harukawa.drivetext.CommandTableHolder.Companion.CMD_ID_SYNC_UPLOAD

class CommandTableHolder(val context : Context) {
    companion object {
        val ENTRY_TABLE_NAME = "cmdTable"
        val CMD_ID_SYNC_UPLOAD = 1
        val CMD_ID_SYNC_UPDATE = 2
        val CMD_ID_SYNC_DOWNLOAD = 3
        val CMD_ID_SYNC_DRIVE_INFO = 4
        val CMD_ID_SYNC_DRIVE_FILE_INFO = 5
    }

    private val TAG = "CommandTable"
    private val DATABASE_NAME = "commandTable.db"
    private val DATABASE_VERSION = 3

    val dbHelper : SQLiteOpenHelper by lazy {
        object : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE " + ENTRY_TABLE_NAME + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "CMD_TYPE INTEGER,"
                        + "DRIVE_ID TEXT,"
                        + "FILE_NAME TEXT,"
                        + "FILE_PATH TEXT,"
                        + "DRIVE_Q TEXT,"
                        + "DRIVE_SPACES TEXT,"
                        + "DRIVE_MIMETYPE TEXT,"
                        + "DRIVE_FIELDS TEXT"
                        + ");");
            }

            fun recreate(db: SQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS ${ENTRY_TABLE_NAME}")
                onCreate(db)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                        + newVersion + ", which will destroy all old data");
                recreate(db);
            }
        }
    }
    val database by lazy {
        dbHelper.writableDatabase!!
    }

    fun close() {
        dbHelper.close()
    }

    class PendingCommand(var id : Long, var cmd: Int)
}

fun CommandTableHolder.query(tableName: String, body: SelectBuilder.()->Unit) : Cursor{
    val builder = SelectBuilder(tableName)
    builder.body()
    return builder.exec(this.database)
}

fun CommandTableHolder.insertEntry(cmd : Int, id : String, fileName : String,
                                   filePath : String, q : String, spaces : String, mimeType : String ) {
    val values = ContentValues()
    values.put("CMD_TYPE", cmd)
    values.put("DRIVE_ID", id)
    values.put("FILE_NAME", fileName)
    values.put("FILE_PATH", filePath)
    values.put("DRIVE_Q", q)
    values.put("DRIVE_SPACES", spaces)
    values.put("DRIVE_MIMETYPE", mimeType)

    database.insert(CommandTableHolder.ENTRY_TABLE_NAME, null, values)
}

fun CommandTableHolder.insertUpload(fileName: String, filePath: String ){
    val values = ContentValues()
    values.put("CMD_TYPE", CMD_ID_SYNC_UPLOAD)
    values.put("FILE_NAME", fileName)
    values.put("FILE_PATH", filePath)

    database.insert(CommandTableHolder.ENTRY_TABLE_NAME, null, values)
}

fun CommandTableHolder.insertUpdate(id : String, fileName: String, filePath: String) {
    val values = ContentValues()
    values.put("CMD_TYPE", CMD_ID_SYNC_UPDATE)
    values.put("DRIVE_ID", id)
    values.put("FILE_NAME", fileName)
    values.put("FILE_PATH", filePath)

    database.insert(CommandTableHolder.ENTRY_TABLE_NAME, null, values)
}

fun CommandTableHolder.insertDownload(id: String, fileName: String) {
    val values = ContentValues()
    values.put("CMD_TYPE", CMD_ID_SYNC_DOWNLOAD)
    values.put("DRIVE_ID", id)
    values.put("FILE_NAME", fileName)

    database.insert(CommandTableHolder.ENTRY_TABLE_NAME, null, values)
}

fun CommandTableHolder.insertGetDriveInfo(q : String, spaces : String, mimeType : String) {
    val values = ContentValues()
    values.put("CMD_TYPE", CMD_ID_SYNC_DRIVE_INFO)
    values.put("DRIVE_Q", q)
    values.put("DRIVE_SPACES", spaces)
    values.put("DRIVE_MIMETYPE", mimeType)

    database.insert(CommandTableHolder.ENTRY_TABLE_NAME, null, values)
}

fun CommandTableHolder.insertGetFileInfo(comPar : CommunicationParameters) {
    val values = ContentValues()
    values.put("CMD_TYPE", CMD_ID_SYNC_DRIVE_FILE_INFO)
    values.put("FILE_NAME", comPar.fileName)
    values.put("DRIVE_Q", comPar.q)
    values.put("DRIVE_SPACES", comPar.spaces)
    values.put("DRIVE_FIELDS", comPar.fields)

    database.insert(CommandTableHolder.ENTRY_TABLE_NAME, null, values)
}

fun CommandTableHolder.deleteEntries(ids: Long) {
    database.delete(CommandTableHolder.ENTRY_TABLE_NAME, "_id=?", arrayOf(ids.toString()))
}

fun CommandTableHolder.queryPendingCursor() : Cursor {
    return database.query(CommandTableHolder.ENTRY_TABLE_NAME, arrayOf("_id", "CMD_TYPE"),
        null,null,null,null,"_id ASC")
}


fun CommandTableHolder.getPendingList() : List<CommandTableHolder.PendingCommand> {
    var res :List<CommandTableHolder.PendingCommand> = mutableListOf()
    var cursor = queryPendingCursor()
    try {
        if(cursor.count==0){
            return res
        }
        cursor.moveToFirst()
        do {
            res += CommandTableHolder.PendingCommand(cursor.getLong(0), cursor.getInt(1))
        }while(cursor.moveToNext())
    }finally {
        if(cursor!=null) {
            cursor.close()
        }
    }
    return res
}

fun CommandTableHolder.getUploadInfo(id : Long) : Pair<String,String> {
    // Get FILE_NAME and FILE_PATH
    return query(CommandTableHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            Pair("","")
        } else {
            Pair(this.getString(3), this.getString(4))
        }
    }
}

fun CommandTableHolder.getUpdateInfo(id : Long) : Triple<String, String,String> {
    // Get DRIVE_ID and FILE_NAME and FILE_PATH
    return query(CommandTableHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            Triple("","","")
        } else {
            Triple(this.getString(2),
                this.getString(3), this.getString(4))
        }
    }
}

fun CommandTableHolder.getDownloadInfo(id : Long) : Pair<String,String> {
    // Get DRIVE_ID and FILE_NAME
    return query(CommandTableHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            Pair("","")
        } else {
            Pair(this.getString(2), this.getString(3))
        }
    }
}

fun CommandTableHolder.getDriveInfo(id : Long) : Triple<String, String,String> {
    // Get DRIVE_Q and DRIVE_SPACES and DRIVE_MIMETYPE
    return query(CommandTableHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            Triple("","","")
        } else {
            Triple(this.getString(5),
                this.getString(6), this.getString(7))
        }
    }
}

fun CommandTableHolder.getDriveFileInfo(id : Long) : CommunicationParameters {
    // Get DRIVE_Q and DRIVE_SPACES and DRIVE_MIMETYPE
    return query(CommandTableHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            CommunicationParameters()
        } else {
            CommunicationParameters("",this.getString(3),"",
                this.getString(5),
                this.getString(6), this.getString(8))
        }
    }
}

fun CommandTableHolder.deleteAll() {
    database.delete(CommandTableHolder.ENTRY_TABLE_NAME, null,null)
}