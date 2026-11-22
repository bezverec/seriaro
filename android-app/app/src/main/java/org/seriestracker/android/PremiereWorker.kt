package org.seriestracker.android

import android.content.Context
import androidx.work.WorkerParameters
import org.seriaro.android.PremiereWorker as SeriaroPremiereWorker

// WorkManager persists this class name for premiere checks scheduled by older releases.
internal class PremiereWorker(context: Context, parameters: WorkerParameters) :
    SeriaroPremiereWorker(context, parameters)
