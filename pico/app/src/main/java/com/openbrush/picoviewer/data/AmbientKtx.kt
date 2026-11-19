package com.openbrush.picoviewer.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** A constant radiance cubemap, with the raw 3-band SH used by Filament's KTX IBL loader.
 * Authored here from the sketch color; contains no PICO SDK environment assets. */
object AmbientKtx {
    fun linear(x:Float)=if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)
    // R11G11B10_UFLOAT: five exponent bits, six/six/five unsigned mantissa bits.
    private fun channel(x:Float,m:Int):Int {
        if(x<=0f)return 0
        val exponent=floor(log2(x.toDouble())).toInt()
        if(exponent < -14)return (x*2.0.pow(14+m)).roundToInt().coerceAtMost((1 shl m)-1)
        val mantissa=((x/2.0.pow(exponent)-1)*(1 shl m)).roundToInt()
        return (((exponent+15) shl m)+mantissa).coerceIn(0,(30 shl m)+((1 shl m)-1))
    }
    fun bytes(srgb:List<Float>):ByteArray {
        require(srgb.size==3 && srgb.all{it.isFinite() && it in 0f..1f})
        val rgb=srgb.map(::linear)
        val pixel=channel(rgb[0],6) or (channel(rgb[1],6) shl 11) or (channel(rgb[2],5) shl 22)
        val sh="sh\u0000"+rgb.joinToString(" "){(it*sqrt(4*PI)).toString()}+"\n"+"0 0 0\n".repeat(8)
        val raw=(sh+"\u0000").toByteArray(Charsets.UTF_8)
        val padded=(raw.size+3)/4*4
        val out=ByteArrayOutputStream()
        fun ints(vararg values:Int){out.write(ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{values.forEach(::putInt)}.array())}
        out.write(byteArrayOf(0xAB.toByte(),0x4B,0x54,0x58,0x20,0x31,0x31,0xBB.toByte(),0x0D,0x0A,0x1A,0x0A))
        ints(0x04030201,0x8C3A,1,0x1907,0x8C3A,0x8C3A,16,16,0,0,6,5,4+padded)
        ints(raw.size);out.write(raw);repeat(padded-raw.size){out.write(0)}
        for(level in 0..4) {val edge=16 shr level;ints(edge*edge*4);repeat(6*edge*edge){ints(pixel)}}
        return out.toByteArray()
    }
    /** Same Z reflection as TiltGeometry; native lights already emit along -Z. */
    fun picoQuaternion(q:List<Float>)=listOf(-q[0],-q[1],q[2],q[3])
}
