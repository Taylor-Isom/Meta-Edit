package com.example.metaedit

import android.app.Application
import com.example.metaedit.data.LibraryPrefs
import com.example.metaedit.data.MediaLibrary
import com.example.metaedit.data.MetadataRepository

class MetaEditApp : Application() {
    val repository by lazy { MetadataRepository(this) }
    val library by lazy { MediaLibrary(this) }
    val libraryPrefs by lazy { LibraryPrefs(this) }
}
