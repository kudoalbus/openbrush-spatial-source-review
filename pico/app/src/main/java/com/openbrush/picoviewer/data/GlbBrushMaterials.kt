package com.openbrush.picoviewer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BrushMaterialReport(val selfLit:Int=0,val additive:Int=0,val transparent:Int=0)

/** KHR unlit retains vertex RGBA and texture sampling; native ADD is applied after entity loading. */
object GlbBrushMaterials {
    fun convert(input:File,output:File,catalog:BrushRenderCatalog):BrushMaterialReport {
        val j=GlbInspector.json(input)
        val report=apply(j,catalog)
        if(report.selfLit>0)GlbContainer.write(input,output,j,GlbContainer.bin(input),ByteArray(0))
        return report
    }
    fun apply(j:JSONObject,catalog:BrushRenderCatalog):BrushMaterialReport {
        val materials=j.optJSONArray("materials") ?: return BrushMaterialReport()
        var selfLit=0;var additive=0;var transparent=0
        for(i in 0 until materials.length()) {
            val material=materials.getJSONObject(i)
            if(material.optJSONObject("extras")?.has("openBrushNativeRendering")==true)continue
            val rule=catalog.match(material)?.takeIf{it.selfLit} ?: continue
            val mode=when(rule.blend) {
                "PRESERVE"->when(material.optString("alphaMode","OPAQUE")){"MASK"->"MASK";"BLEND"->"ALPHA";else->"OPAQUE"}
                else->rule.blend
            }
            val originalName=material.optString("name")
            val extras=material.optJSONObject("extras") ?: JSONObject().also{material.put("extras",it)}
            extras.put("openBrushNativeRendering",JSONObject().put("originalName",originalName).put("brush",rule.name)
                .put("guid",rule.guid).put("blend",mode).put("depthWrite",rule.depthWrite))
            // Safe characters survive glTF -> native material-name conversion.
            material.put("name","OBV_${mode}_${if(rule.depthWrite)1 else 0}_${rule.guid.replace("-","")}__$originalName")
            val extensions=material.optJSONObject("extensions") ?: JSONObject().also{material.put("extensions",it)}
            extensions.put("KHR_materials_unlit",JSONObject())
            val pbr=material.optJSONObject("pbrMetallicRoughness") ?: JSONObject().also{material.put("pbrMetallicRoughness",it)}
            pbr.put("metallicFactor",0)
            when(mode) {
                "ADD","ALPHA"->{material.put("alphaMode","BLEND");material.remove("alphaCutoff")}
                "MASK"->{material.put("alphaMode","MASK");if(!material.has("alphaCutoff"))material.put("alphaCutoff",rule.cutoff)}
                else->{material.put("alphaMode","OPAQUE");material.remove("alphaCutoff")}
            }
            selfLit++;if(mode=="ADD")additive++;if(mode in setOf("ADD","ALPHA"))transparent++
        }
        if(selfLit>0) {
            val used=j.optJSONArray("extensionsUsed") ?: JSONArray().also{j.put("extensionsUsed",it)}
            if((0 until used.length()).none{used.getString(it)=="KHR_materials_unlit"})used.put("KHR_materials_unlit")
        }
        return BrushMaterialReport(selfLit,additive,transparent)
    }
}
