// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.impl.jar;

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.io.URLUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.intellij.openapi.util.Pair.pair;

/**
 * KSP's copy of the class of intellij-core, with {@link #clearChangedHandlers()}: when KSP keeps its class loaders in the
 * daemon (ksp.classloader.cache), a KSP task drops only the handlers of the jars that changed, so that the next task does not
 * read the entries of all jars of its classpath again.
 */
public final class CoreJarFileSystem extends DeprecatedVirtualFileSystem {
  // The size and the time of the jar of each handler when it was made.
  private final Map<String, String> myFingerprints = new ConcurrentHashMap<>();

  private final Map<String, CoreJarHandler> myHandlers = ConcurrentFactoryMap.createMap(key -> {
    myFingerprints.put(key, fingerprint(key));
    return new CoreJarHandler(this, key);
  });

  @Override
  public @NotNull String getProtocol() {
    return StandardFileSystems.JAR_PROTOCOL;
  }

  @Override
  public VirtualFile findFileByPath(@NotNull String path) {
    Pair<String, String> pair = splitPath(path);
    return myHandlers.get(pair.first).findFileByPath(pair.second);
  }

  static @NotNull Pair<String, String> splitPath(@NotNull String path) {
    int separator = path.indexOf(URLUtil.JAR_SEPARATOR);
    if (separator < 0) throw new IllegalArgumentException("Path in JarFileSystem must contain a separator: " + path);
    return pair(path.substring(0, separator), path.substring(separator + URLUtil.JAR_SEPARATOR.length()));
  }

  @Override
  public void refresh(boolean asynchronous) { }

  @Override
  public VirtualFile refreshAndFindFileByPath(@NotNull String path) {
    return findFileByPath(path);
  }

  public void clearHandlersCache() {
    myHandlers.forEach((path, handler) -> handler.clearCaches());
    myHandlers.clear();
    myFingerprints.clear();
  }

  /** Drops the handlers of the jars whose size or time changed since the handler was made, or which no longer exist. */
  public void clearChangedHandlers() {
    for (Map.Entry<String, CoreJarHandler> entry : myHandlers.entrySet()) {
      String path = entry.getKey();
      String fingerprint = fingerprint(path);
      if (fingerprint == null || !fingerprint.equals(myFingerprints.get(path))) {
        entry.getValue().clearCaches();
        myHandlers.remove(path);
        myFingerprints.remove(path);
      }
    }
  }

  private static String fingerprint(String path) {
    File file = new File(path);
    return file.isFile() ? file.length() + ":" + file.lastModified() : null;
  }
}
