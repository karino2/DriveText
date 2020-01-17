package com.github.harukawa.drivetext

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.*

class DatabaseHolder(val context: Context){
    companion object {
        val ENTRY_TABLE_NAME = "drive"
    }
    private val TAG = "DriveText"
    private val DATABASE_NAME = "fileDB.db"
    private val DATABASE_VERSION = 3

    val dbHelper : SQLiteOpenHelper by lazy {
        object : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE " + ENTRY_TABLE_NAME + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "FILE_NAME TEXT,"
                        + "FILE_ID TEXT,"
                        + "LOCAL_FILE_DATE LONG,"
                        + "DRIVE_FILE_DATE LONG"
                        + ");");
            }

            fun recreate(db: SQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS $ENTRY_TABLE_NAME")
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
}


class SelectBuilder(val tableName: String) {
    var distinct = false
    var columns = arrayOf<String>()
    var selection : String? = null
    var selectionArgs = arrayOf<String>()
    var groupBy : String? = null
    var having : String? = null
    var orderBy : String? = null

    var limit : String? = null

    fun select(vararg fields: String) {
        columns = arrayOf(*fields)
    }

    fun order(sentence: String) {
        orderBy = sentence
    }

    fun where(whereSentence: String, vararg args: String) {
        selection = whereSentence
        selectionArgs = arrayOf(*args)
    }


    fun exec(db: SQLiteDatabase) : Cursor {
        val columnsArg = if(columns.isEmpty()) null else columns
        val selectionArgsArg = if(selectionArgs.isEmpty()) null else selectionArgs

        return db.query(distinct, tableName, columnsArg, selection, selectionArgsArg, groupBy, having, orderBy, limit)
    }
}

fun DatabaseHolder.query(tableName: String, body: SelectBuilder.()->Unit) : Cursor{
    val builder = SelectBuilder(tableName)
    builder.body()
    return builder.exec(this.database)
}

fun DatabaseHolder.insertEntry(fileName: String = "",fileId: String, localDate: Date, driveDate: Date) {
    val values = ContentValues()
    values.put("FILE_NAME", fileName)
    values.put("FILE_ID", fileId)
    values.put("LOCAL_FILE_DATE", localDate.time.toLong())
    values.put("DRIVE_FILE_DATE", driveDate.time.toLong())

    database.insert(DatabaseHolder.ENTRY_TABLE_NAME, null, values)
}

fun DatabaseHolder.updateEntry(id: Long, fileName: String, fileId: String,localDate: Date, driveDate: Date) {
    val values = ContentValues()
    values.put("FILE_NAME", fileName)
    values.put("FILE_ID", fileId)
    values.put("LOCAL_FILE_DATE", localDate.time.toLong())
    values.put("DRIVE_FILE_DATE", driveDate.time.toLong())
    database.update(DatabaseHolder.ENTRY_TABLE_NAME, values, "_id=?", arrayOf(id.toString()))
}

fun DatabaseHolder.updateEntry(id: Long, fileName: String, localDate: Date, driveDate: Date) {
    val values = ContentValues()
    values.put("FILE_NAME", fileName)
    values.put("LOCAL_FILE_DATE", localDate.time.toLong())
    values.put("DRIVE_FILE_DATE", driveDate.time.toLong())
    database.update(DatabaseHolder.ENTRY_TABLE_NAME, values, "_id=?", arrayOf(id.toString()))
}

fun DatabaseHolder.updateEntry(id: Long, fileName: String, fileId: String,localDate: Date) {
    val values = ContentValues()
    values.put("FILE_NAME", fileName)
    values.put("FILE_ID", fileId)
    values.put("LOCAL_FILE_DATE", localDate.time.toLong())
    database.update(DatabaseHolder.ENTRY_TABLE_NAME, values, "_id=?", arrayOf(id.toString()))
}

fun DatabaseHolder.updateDriveEntry(id: Long, localFile: LocalFile, driveDate: Date) {
    val values = ContentValues()
    values.put("FILE_NAME", localFile.name)
    values.put("FILE_ID", localFile.fileId)
    values.put("DRIVE_FILE_DATE", driveDate.time.toLong())
    database.update(DatabaseHolder.ENTRY_TABLE_NAME, values, "_id=?", arrayOf(id.toString()))
}

fun DatabaseHolder.deleteEntries(ids: List<Long>) {
    ids.forEach {
        database.delete(DatabaseHolder.ENTRY_TABLE_NAME, "_id=?", arrayOf(it.toString()))
    }
}

inline fun <reified T> Cursor.withClose(body: Cursor.()->T) : T{
    val res = body()
    this.close()
    return res
}

fun DatabaseHolder.getLocalFile(id: Long): LocalFile {
    return query(DatabaseHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            LocalFile("", "", 0L)
        } else {
            LocalFile(this.getString(1), this.getString(2), this.getLong(3))
        }
    }
}

fun DatabaseHolder.getData(driveId : String): Pair<String, Long> {
    return query(DatabaseHolder.ENTRY_TABLE_NAME) {
        where("FILE_ID=?",driveId)
    }.withClose{
        moveToFirst()
        if(isAfterLast){
            Pair("", 0L)
        } else {
            // FILE_NAME, DRIVE_FILE_DATE
            Pair(this.getString(1), this.getLong(4))
        }
    }
}

fun DatabaseHolder.getDbDate(id: Long): Long{
    return query(DatabaseHolder.ENTRY_TABLE_NAME) {
        where("_id=?", id.toString())
    }.withClose{
        moveToFirst()
        if(isAfterLast) {
            0L
        } else {
            this.getLong(4)
        }
    }
}

fun DatabaseHolder.getId(fileName: String): Long {
    return query(DatabaseHolder.ENTRY_TABLE_NAME) {
        where("FILE_NAME=?",fileName)
    }.withClose{
        moveToFirst()
        if(isAfterLast){
            -1
        } else {
            this.getLong(0)
        }
    }
}

fun DatabaseHolder.getId(fileName: String, fileId: String): Long {
    return query(DatabaseHolder.ENTRY_TABLE_NAME) {
        where("FILE_NAME=? AND FILE_ID=?",fileName, fileId)
    }.withClose{
        moveToFirst()
        if(isAfterLast){
            -1
        } else {
            this.getLong(0)
        }
    }
}