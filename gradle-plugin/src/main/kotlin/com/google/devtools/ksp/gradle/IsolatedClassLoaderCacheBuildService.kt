/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.ksp.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.net.URLClassLoader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object IsolatedClassLoaderCache {
    // The class loaders of the current build; the build service closes them at the end of the build.
    val cache = ConcurrentHashMap<String, URLClassLoader>()

    private class Entry(val fingerprint: List<String>, val loader: URLClassLoader)

    // With ksp.classloader.cache: the class loaders of KSP and of the processors, kept from one task and one build to the
    // next in the daemon, so that their classes are loaded and compiled by the JIT once.
    private val kept = ConcurrentHashMap<String, Entry>()

    /**
     * A class loader for [files] with [parent], kept across tasks and builds. It is made again when a file changes (its size or
     * time). A loader that is replaced is not closed, because another task can still use it; it is unloaded when it is unused.
     * Null if [files] has a directory, whose contents the fingerprint does not see: the caller then makes its own loader.
     */
    fun keptLoader(key: String, files: List<File>, parent: ClassLoader): URLClassLoader? {
        if (files.any { !it.isFile }) return null
        val fingerprint = files.map { "${it.path}:${it.length()}:${it.lastModified()}" }
        return kept.compute(key) { _, old ->
            if (old != null && old.fingerprint == fingerprint) old
            else Entry(fingerprint, URLClassLoader(files.map { it.toURI().toURL() }.toTypedArray(), parent))
        }!!.loader
    }

    fun clear() {
        cache.values.forEach { classLoader ->
            try {
                classLoader.close()
            } catch (e: Exception) {
                // Ignore exceptions during cleanup, but we could log them if a logger was available.
            }
        }
        cache.clear()
    }
}

abstract class IsolatedClassLoaderCacheBuildService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    companion object {
        val KEY = "IsolatedClassLoaderCacheBuildService_" + UUID.randomUUID().toString()
    }

    override fun close() {
        IsolatedClassLoaderCache.clear()
    }
}
