package io.github.karino2.listtextview

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText

class EditCellActivity : AppCompatActivity() {

    var cellId = -1


    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("CELL_ID", cellId)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        cellId = savedInstanceState.getInt("CELL_ID")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_cell)

        intent?.let {
            cellId = it.getIntExtra("CELL_ID", -1)
            editText.setText(it.getStringExtra("CELL_CONTENT"))
        }

        editText.setOnKeyListener { view, keyCode, keyEvent ->
            if(keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.isShiftPressed) {
                finishAsCommitCell()
                true
            } else {
                false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_edit_cell, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.save_item -> {
                finishAsCommitCell()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun finishAsCommitCell() {
        Intent().apply {
            this.putExtra("CELL_ID", cellId)
            this.putExtra("CELL_CONTENT", editText.text.toString())
        }.also {
            setResult(RESULT_OK, it)
        }
        finish()
    }

    val editText by lazy {
        findViewById<EditText>(R.id.editText)
    }
}
