package com.openbrush.picoviewer.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

data class FolderDocument(val path:String,val uri:Uri)

/** Only traverses the user-granted document tree; no filesystem-path guessing. */
class DocumentTree(private val resolver:ContentResolver,private val treeUri:Uri) {
    fun scan():List<FolderDocument> {
        val result=mutableListOf<FolderDocument>();var visited=0
        val seen=mutableSetOf<String>()
        fun visit(id:String,prefix:String,depth:Int) {
            require(depth<=12){"文件夹层级超过 12 层，请选择模型所在的子文件夹"}
            require(seen.add(id)){"文件夹包含循环引用"}
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,id)
            val projection=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)
            val items:List<Triple<String,String,String>> = resolver.query(children,projection,null,null,null)?.use { c ->
                buildList<Triple<String,String,String>> { while(c.moveToNext()) {
                    require(++visited<=4096){"文件夹包含过多文件，请为作品单独建立文件夹"}
                    add(Triple(c.getString(0),c.getString(1),c.getString(2)))
                } }
            } ?: error("无法读取所选文件夹")
            for((childId,name,mime) in items) {
                require(name.isNotEmpty() && name!="." && name!=".." && '/' !in name && '\\' !in name){"文件名无效"}
                val path=prefix+name
                if(mime==DocumentsContract.Document.MIME_TYPE_DIR)visit(childId,"$path/",depth+1)
                else result.add(FolderDocument(path,DocumentsContract.buildDocumentUriUsingTree(treeUri,childId)))
            }
        }
        visit(DocumentsContract.getTreeDocumentId(treeUri),"",0)
        return result
    }
}
