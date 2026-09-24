package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.easycoderemote.EasyCodeRemoteApp
import com.easycoderemote.data.repo.Repository

/** Convenience base: exposes the app-scoped Repository to all ViewModels. */
abstract class RepoViewModel(app: Application) : AndroidViewModel(app) {
    protected val repo: Repository
        get() = (getApplication<Application>() as EasyCodeRemoteApp).repository
}