package com.lele.novelmaster

import android.app.Application
import com.lele.novelmaster.data.Repo

class NovelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
