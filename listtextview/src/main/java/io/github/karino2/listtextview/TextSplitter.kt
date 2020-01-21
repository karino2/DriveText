package io.github.karino2.listtextview

import android.content.Context
import android.widget.ArrayAdapter

class TextSplitter {
    var text = ""
    set(newValue) {
        _needRefresh = true
        field = newValue
    }

    private var _needRefresh = true
    private var _textList : ArrayList<String> = ArrayList()
    val textList :ArrayList<String>
        get() {
            if(_needRefresh) {
                _textList.clear()
                if(text != "")
                    _textList.addAll(text.split("\n\n"))
                _needRefresh = false
            }
            return _textList
        }

    val mergedContent: String
    get() = textList.joinToString("\n\n")

    fun createAdapter(context: Context) : ArrayAdapter<String> = ArrayAdapter(context, R.layout.list_item, textList)
}

/*
val ArrayAdapter<String>.mergeContent: String
    get() {
        val adapter = this
        return sequence {
            repeat(adapter.count) {
                yield(adapter.getItem(it))
            }
        }.joinToString("\n")
    }

 */
