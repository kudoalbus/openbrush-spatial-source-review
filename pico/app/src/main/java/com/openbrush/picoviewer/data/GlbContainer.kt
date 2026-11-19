package com.openbrush.picoviewer.data

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams geometry without allocating a second model-sized byte array. */
object GlbContainer {
    data class Bin(val start:Long,val length:Int) { val alignedLength:Int get()=(length+3)/4*4 }
    fun bin(file:File):Bin = RandomAccessFile(file,"r").use { source ->
        source.seek(12)
        val jsonLength=Integer.reverseBytes(source.readInt())
        val header=20L+jsonLength
        require(header+8<=source.length()){"GLB 缺少内嵌 BIN"}
        source.seek(header)
        val length=Integer.reverseBytes(source.readInt())
        require(Integer.reverseBytes(source.readInt())==0x004e4942 && length>=0 && header+8+length==source.length()){"GLB BIN 块无效"}
        Bin(header+8,length)
    }
    fun write(input:File,output:File,document:JSONObject,bin:Bin,append:ByteArray) {
        require(input.canonicalFile!=output.canonicalFile)
        require(append.size%4==0)
        val json=document.toString().toByteArray(Charsets.UTF_8)
        val padded=(json.size+3)/4*4
        val length=bin.alignedLength+append.size
        require(28L+padded+length<=384L*1024*1024){"补齐资源后的 GLB 超过 384 MB，请精简模型"}
        output.outputStream().buffered().use { out ->
            out.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(28+padded+length).putInt(padded).putInt(0x4e4f534a).array())
            out.write(json);repeat(padded-json.size){out.write(32)}
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(length).putInt(0x004e4942).array())
            RandomAccessFile(input,"r").use { source ->
                source.seek(bin.start);var remaining=bin.length;val buffer=ByteArray(65536)
                while(remaining>0){val n=minOf(remaining,buffer.size);source.readFully(buffer,0,n);out.write(buffer,0,n);remaining-=n}
            }
            repeat(bin.alignedLength-bin.length){out.write(0)}
            out.write(append)
        }
    }
}
