package com.offline.dpadmessenger.app

import android.app.Application

/**
 * Process-wide singleton. Nothing to do at startup yet — backend
 * initialisation is lazy, triggered by [AppViewModel] when the UI mounts.
 */
class DpadApp : Application()
