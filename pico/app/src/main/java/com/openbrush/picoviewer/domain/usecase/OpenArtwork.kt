package com.openbrush.picoviewer.domain.usecase

import com.openbrush.picoviewer.data.ImportRequest
import com.openbrush.picoviewer.data.repository.ArtworkRepository

class OpenArtwork(private val repository:ArtworkRepository) {
    suspend operator fun invoke(request:ImportRequest?) = if(request==null) repository.demo() else repository.import(request)
}
