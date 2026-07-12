package com.pawchive

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.pawchive.data.SettingsManager
import java.io.File

class PawchiveApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val IMAGE_DISK_CACHE_DIR = "image_cache"
        private const val IMAGE_DISK_CACHE_MAX_SIZE = 100L * 1024 * 1024
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, IMAGE_DISK_CACHE_DIR))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_SIZE)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    fun clearCache() {
        try {
            coil.ImageLoader(this).memoryCache?.clear()
        } catch (_: Exception) {}

        try {
            val diskCacheDir = File(cacheDir, IMAGE_DISK_CACHE_DIR)
            if (diskCacheDir.exists()) {
                diskCacheDir.deleteRecursively()
            }
        } catch (_: Exception) {}

        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name != IMAGE_DISK_CACHE_DIR) {
                    file.deleteRecursively()
                }
            }
        } catch (_: Exception) {}

        try {
            externalCacheDir?.deleteRecursively()
        } catch (_: Exception) {}
    }
}