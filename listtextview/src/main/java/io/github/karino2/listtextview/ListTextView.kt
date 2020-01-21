package io.github.karino2.listtextview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.*

class ListTextView(val cont: Context, attrs: AttributeSet) : RelativeLayout(cont, attrs) {

    // user of this view should inject these fields
    lateinit var owner: Activity
    var editActivityRequestCode: Int = 0


    val textSplitter: TextSplitter = TextSplitter()
    var listView : ListView


    init {
        inflate(context, R.layout.list_text_view, this)
        listView = findViewById<ListView>(R.id.listView)
        listView.adapter = textSplitter.createAdapter(context)

        listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE_MODAL
        listView.setOnItemClickListener{ adapterView, view, id, pos ->
            startEditCellActivityForResult(id, listView.adapter.getItem(id) as String)
        }

        findViewById<Button>(R.id.buttonNew).setOnClickListener {
            startEditCellActivityForResult(-1, "")
        }
    }


    var text: String
    set(newValue) {
        textSplitter.text = newValue
        listView.adapter = textSplitter.createAdapter(context)
    }
    get() = textSplitter.mergedContent

    val adapter: ArrayAdapter<String>
    get() = listView.adapter as ArrayAdapter<String>


    private fun startEditCellActivityForResult(cellId: Int, content: String) {
        Intent(owner, EditCellActivity::class.java).apply {
            this.putExtra("CELL_ID", cellId)
            this.putExtra("CELL_CONTENT", content)
        }.also {
            owner.startActivityForResult(it, editActivityRequestCode)
        }
    }

    fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent) : Boolean {
        if(requestCode == editActivityRequestCode) {
            if(resultCode == Activity.RESULT_OK) {
                val cellId = data.getIntExtra("CELL_ID", -1)
                val content = data.getStringExtra("CELL_CONTENT")!!
                if(cellId == -1) {
                    // Caution! adapter's back must be textSplitter.textList.
                    adapter.add(content)
                    adapter.notifyDataSetInvalidated()
                } else {
                    textSplitter.textList[cellId] = content
                    adapter.notifyDataSetChanged()
                }
            }
            return true
        }
        return false
    }


    // save instance state related code
    class SavedState : BaseSavedState {
        var content= ""

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(content)
        }


        constructor(source: Parcel) : super(source) {
            content = source.readString()!!
        }

        constructor(superState: Parcelable) : super(superState)

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState {
                    return SavedState(source)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()!!
        val state = SavedState(superState)
        state.content = text
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if(state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            text = state.content
        } else {
            super.onRestoreInstanceState(state)
        }
    }
}