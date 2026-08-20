package com.nosmai.camerasdk.reactnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nosmai.effect.api.NosmaiCloud
import com.nosmai.effect.api.NosmaiCloudFilterVersion
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Receives cloud events after they have been normalized for the JS contract. */
internal fun interface NosmaiAndroidCloudDelegate {
  fun onCloudDownloadProgress(filterId: String, progress: Double)
}

internal data class NosmaiAndroidCloudQuery(
  val packageType: String? = null,
  val version: String = SUPPORTED_CLOUD_VERSION,
  /** Zero is the JS bridge sentinel for an omitted page. */
  val page: Int = 0,
  val limit: Int = 20,
  val fetchAllPages: Boolean = page == 0
)

internal class NosmaiCloudException(
  val code: String,
  override val message: String,
  override val cause: Throwable? = null
) : Exception(message, cause)

internal object NosmaiCloudErrorCode {
  const val INVALID_ARGUMENT = "E_INVALID_ARGUMENT"
  const val CLOUD_DISABLED = "E_CLOUD_DISABLED"
  const val CLOUD_CATALOG = "E_CLOUD_CATALOG"
  const val CLOUD_DOWNLOAD = "E_CLOUD_DOWNLOAD"
  const val CLOUD_DOWNLOAD_TIMEOUT = "E_CLOUD_DOWNLOAD_TIMEOUT"
  const val CLOUD_REMOVE = "E_CLOUD_REMOVE"
  const val SESSION_DESTROYED = "E_SESSION_DESTROYED"
}

/**
 * Serializes Android cloud access without coupling it to React Native classes.
 *
 * SDK initialization and module-owner validation stay in the controller. This
 * helper owns only native cloud calls, immutable JS-shaped snapshots, download
 * coalescing, and exact local-package removal.
 */
internal class NosmaiAndroidCloud(
  context: Context,
  private val mainHandler: Handler,
  initialDelegate: NosmaiAndroidCloudDelegate? = null
) {
  private val appContext = context.applicationContext
  private val stopped = AtomicBoolean(false)
  private val callbackLock = Any()
  private val pendingCallbacks = mutableSetOf<PendingCallback>()
  private val downloadLock = Any()
  private val downloads = mutableMapOf<String, DownloadFlight>()

  /** Accessed only on [cloudExecutor]. */
  private val resolvedDownloadPaths = mutableMapOf<String, String>()

  @Volatile
  private var delegate: NosmaiAndroidCloudDelegate? = initialDelegate

  private val cloudExecutor: ExecutorService = Executors.newSingleThreadExecutor(
    namedDaemonThreadFactory("NosmaiCloud")
  )
  private val watchdogExecutor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(
      namedDaemonThreadFactory("NosmaiCloudWatchdog")
    )

  private interface PendingCallback {
    fun reject(error: NosmaiCloudException)
  }

  private inner class Completion<T>(
    private val callback: (T?, NosmaiCloudException?) -> Unit
  ) : PendingCallback {
    private val settled = AtomicBoolean(false)

    fun isSettled(): Boolean = settled.get()

    fun resolve(value: T) {
      finish(value, null)
    }

    override fun reject(error: NosmaiCloudException) {
      finish(null, error)
    }

    private fun finish(value: T?, error: NosmaiCloudException?) {
      if (!settled.compareAndSet(false, true)) return
      synchronized(callbackLock) { pendingCallbacks.remove(this) }
      dispatchToMain { runCatching { callback(value, error) } }
    }
  }

  private data class ValidatedQuery(
    val nativePage: Int,
    val limit: Int,
    val nativeFilterType: String?,
    val outputPackageType: String?,
    val fetchAllPages: Boolean
  )

  private data class PaginationSnapshot(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean
  )

  private data class CatalogSnapshot(
    val items: List<NosmaiCloud.Item>,
    val pagination: PaginationSnapshot
  )

  private data class DownloadFlight(
    val filterId: String,
    val callbacks: MutableList<Completion<Map<String, Any>>> = mutableListOf(),
    var timedOut: Boolean = false,
    var watchdog: ScheduledFuture<*>? = null
  )

  fun setDelegate(value: NosmaiAndroidCloudDelegate?) {
    delegate = value
  }

  fun isEnabled(completion: (Boolean?, NosmaiCloudException?) -> Unit) {
    val callback = registerCompletion<Boolean>(completion)
    submit(
      callback,
      NosmaiCloudErrorCode.CLOUD_CATALOG,
      "Unable to query Nosmai cloud availability"
    ) {
      callback.resolve(NosmaiCloud.isEnabled())
    }
  }

  fun fetch(
    query: NosmaiAndroidCloudQuery,
    completion: (Map<String, Any>?, NosmaiCloudException?) -> Unit
  ) {
    val callback = registerCompletion<Map<String, Any>>(completion)
    if (callback.isSettled()) return

    val validated = try {
      validateQuery(query)
    } catch (error: NosmaiCloudException) {
      callback.reject(error)
      return
    }

    submit(
      callback,
      NosmaiCloudErrorCode.CLOUD_CATALOG,
      "Unable to fetch the Nosmai cloud catalog"
    ) {
      if (!NosmaiCloud.isEnabled()) {
        throw NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_DISABLED,
          "Cloud filters are not enabled for the current Nosmai license"
        )
      }

      val nativeQuery = NosmaiCloud.FilterQuery().apply {
        page = validated.nativePage
        limit = validated.limit
        version = NosmaiCloudFilterVersion.V2
        filterType = validated.nativeFilterType
        fetchAllPages = validated.fetchAllPages
        // A scoped or paginated response is never authoritative for cleanup.
        cleanupRemoved = false
      }

      val snapshot = if (NosmaiCloud.fetch(nativeQuery)) {
        currentCatalogSnapshot(validated.limit)
      } else if (validated.nativeFilterType == null) {
        // The native aggregate request stops when any one category endpoint
        // fails. Individual category requests remain usable in that state (and
        // are also how callers work around this on Flutter), so retry them and
        // merge their successful snapshots instead of rejecting the whole
        // unscoped `All` request.
        fetchMergedCatalogByType(validated)
      } else {
        null
      } ?: throw cloudCatalogFetchError()

      callback.resolve(
        buildCatalogEnvelope(
          snapshot.items,
          snapshot.pagination,
          validated
        )
      )
    }
  }

  /** Must run on [cloudExecutor] so every cached snapshot matches its fetch. */
  private fun currentCatalogSnapshot(fallbackLimit: Int): CatalogSnapshot {
    val items = NosmaiCloud.cachedList()
    val pagination = NosmaiCloud.pagination()
    return CatalogSnapshot(
      items = items,
      pagination = PaginationSnapshot(
        currentPage = pagination.currentPage,
        totalPages = pagination.totalPages,
        totalItems = pagination.totalFilters,
        itemsPerPage = pagination.limit.takeIf { it > 0 } ?: fallbackLimit,
        hasNextPage = pagination.hasNextPage,
        hasPreviousPage = pagination.hasPreviousPage
      )
    )
  }

  /**
   * Recovers an unscoped catalog from the same category endpoints that already
   * work when requested explicitly. A non-empty partial result is preferable
   * to Flutter's empty-success behaviour, while a total failure still rejects.
   */
  private fun fetchMergedCatalogByType(query: ValidatedQuery): CatalogSnapshot? {
    val merged = linkedMapOf<String, NosmaiCloud.Item>()
    var successfulBuckets = 0
    var currentPage = query.nativePage.coerceAtLeast(1)
    var totalPages = 1
    var totalItems = 0
    var hasNextPage = false
    var hasPreviousPage = false

    ALL_CLOUD_NATIVE_TYPES.forEach { nativeType ->
      val nativeQuery = NosmaiCloud.FilterQuery().apply {
        page = query.nativePage
        limit = query.limit
        version = NosmaiCloudFilterVersion.V2
        filterType = nativeType
        fetchAllPages = query.fetchAllPages
        cleanupRemoved = false
      }
      if (!NosmaiCloud.fetch(nativeQuery)) return@forEach

      successfulBuckets += 1
      val bucket = currentCatalogSnapshot(query.limit)
      bucket.items.forEach { item ->
        val id = item.id?.trim().orEmpty()
        if (id.isNotEmpty()) merged.putIfAbsent(id, item)
      }
      currentPage = maxOf(currentPage, bucket.pagination.currentPage.coerceAtLeast(1))
      totalPages = maxOf(totalPages, bucket.pagination.totalPages.coerceAtLeast(1))
      totalItems = safeCatalogTotal(totalItems, bucket.pagination.totalItems)
      hasNextPage = hasNextPage || bucket.pagination.hasNextPage
      hasPreviousPage = hasPreviousPage || bucket.pagination.hasPreviousPage
    }

    if (successfulBuckets == 0 || merged.isEmpty()) return null
    totalItems = maxOf(totalItems, merged.size)
    totalPages = maxOf(totalPages, currentPage)
    if (currentPage >= totalPages) hasNextPage = false
    if (currentPage > 1) hasPreviousPage = true

    return CatalogSnapshot(
      items = merged.values.toList(),
      pagination = PaginationSnapshot(
        currentPage = currentPage,
        totalPages = totalPages,
        totalItems = totalItems,
        itemsPerPage = query.limit,
        hasNextPage = hasNextPage,
        hasPreviousPage = hasPreviousPage
      )
    )
  }

  private fun safeCatalogTotal(current: Int, addition: Int): Int {
    val safeCurrent = current.coerceAtLeast(0)
    val safeAddition = addition.coerceAtLeast(0)
    return if (safeCurrent > Int.MAX_VALUE - safeAddition) {
      Int.MAX_VALUE
    } else {
      safeCurrent + safeAddition
    }
  }

  private fun cloudCatalogFetchError(): NosmaiCloudException {
    val nativeDetail = runCatching { NosmaiCloud.lastError().trim() }
      .getOrNull()
      ?.takeIf(String::isNotEmpty)
    return NosmaiCloudException(
      NosmaiCloudErrorCode.CLOUD_CATALOG,
      nativeDetail ?: "The Nosmai cloud catalog request failed"
    )
  }

  fun download(
    filterId: String,
    completion: (Map<String, Any>?, NosmaiCloudException?) -> Unit
  ) {
    val callback = registerCompletion<Map<String, Any>>(completion)
    if (callback.isSettled()) return

    val normalizedId = normalizedSafeFilterId(filterId)
    if (normalizedId == null) {
      callback.reject(
        NosmaiCloudException(
          NosmaiCloudErrorCode.INVALID_ARGUMENT,
          "filterId must not be empty"
        )
      )
      return
    }

    var flightToStart: DownloadFlight? = null
    var timedOutFlight = false
    synchronized(downloadLock) {
      if (stopped.get()) {
        // registerCompletion normally wins this race; this closes the smaller
        // window between registration and joining the coalesced operation.
      } else {
        val existing = downloads[normalizedId]
        if (existing != null) {
          if (existing.timedOut) {
            timedOutFlight = true
          } else {
            existing.callbacks += callback
          }
        } else {
          flightToStart = DownloadFlight(normalizedId).also {
            it.callbacks += callback
            downloads[normalizedId] = it
          }
        }
      }
    }

    when {
      stopped.get() -> callback.reject(sessionDestroyedError())
      timedOutFlight -> callback.reject(downloadTimeoutError(normalizedId))
      flightToStart != null -> startDownload(flightToStart!!)
    }
  }

  fun remove(
    filterId: String,
    completion: (Boolean?, NosmaiCloudException?) -> Unit
  ) {
    val callback = registerCompletion<Boolean>(completion)
    if (callback.isSettled()) return

    val normalizedId = normalizedSafeFilterId(filterId)
    if (normalizedId == null) {
      callback.reject(
        NosmaiCloudException(
          NosmaiCloudErrorCode.INVALID_ARGUMENT,
          "filterId must not be empty"
        )
      )
      return
    }

    submit(
      callback,
      NosmaiCloudErrorCode.CLOUD_REMOVE,
      "Unable to remove the downloaded Nosmai cloud package"
    ) {
      val downloading = synchronized(downloadLock) {
        downloads.containsKey(normalizedId)
      }
      if (downloading) {
        throw NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_REMOVE,
          "The cloud package cannot be removed while its download is still active"
        )
      }

      val resolvedPath = resolveExactDownloadedPath(normalizedId)
      if (resolvedPath == null) {
        resolvedDownloadPaths.remove(normalizedId)
        callback.resolve(false)
        return@submit
      }

      val packageFile = validateRemovalTarget(resolvedPath)
      if (!packageFile.exists()) {
        resolvedDownloadPaths.remove(normalizedId)
        callback.resolve(false)
        return@submit
      }
      if (!packageFile.isFile) {
        throw NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_REMOVE,
          "The resolved cloud package path is not a file"
        )
      }

      if (!packageFile.delete() && packageFile.exists()) {
        throw NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_REMOVE,
          "The downloaded Nosmai cloud package could not be deleted"
        )
      }

      resolvedDownloadPaths.remove(normalizedId)
      // The pinned public NosmaiCloud ABI has no cache mutation/remove method.
      // cachedList() derives isDownloaded from file existence, so deleting this
      // one exact file reconciles its next public snapshot without a directory
      // scan or an unsafe filter-ID substring match.
      callback.resolve(true)
    }
  }

  /**
   * Stops helper-owned work. NosmaiCloud exposes no download cancellation API;
   * a native download already in progress may still finish after this returns.
   */
  fun shutdown() {
    if (!stopped.compareAndSet(false, true)) return
    delegate = null

    synchronized(downloadLock) {
      downloads.values.forEach { it.watchdog?.cancel(false) }
      downloads.clear()
    }

    val pending = synchronized(callbackLock) { pendingCallbacks.toList() }
    val shutdownError = sessionDestroyedError()
    pending.forEach { it.reject(shutdownError) }

    cloudExecutor.shutdownNow()
    watchdogExecutor.shutdownNow()
  }

  private fun startDownload(flight: DownloadFlight) {
    try {
      cloudExecutor.execute {
        if (!isActive(flight) || stopped.get()) return@execute
        try {
          if (!NosmaiCloud.isEnabled()) {
            finishDownload(
              flight,
              null,
              NosmaiCloudException(
                NosmaiCloudErrorCode.CLOUD_DISABLED,
                "Cloud filters are not enabled for the current Nosmai license"
              )
            )
            return@execute
          }

          findStrictCachedPackage(flight.filterId)?.let { packageFile ->
            resolvedDownloadPaths[flight.filterId] = packageFile.path
            finishDownload(
              flight,
              downloadResult(flight.filterId, packageFile.path, true),
              null
            )
            return@execute
          }

          val watchdog = try {
            watchdogExecutor.schedule(
              { onDownloadWatchdog(flight) },
              DOWNLOAD_WATCHDOG_MILLIS,
              TimeUnit.MILLISECONDS
            )
          } catch (error: RejectedExecutionException) {
            finishDownload(
              flight,
              null,
              NosmaiCloudException(
                NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
                "The Nosmai cloud download watchdog is unavailable",
                error
              )
            )
            return@execute
          }

          val stillActive = synchronized(downloadLock) {
            if (downloads[flight.filterId] === flight && !flight.timedOut) {
              flight.watchdog = watchdog
              true
            } else {
              false
            }
          }
          if (!stillActive || stopped.get()) {
            watchdog.cancel(false)
            return@execute
          }

          // Keep Android on the stable no-progress SDK overload. The native
          // progress overload currently crashes inside libnosmai.so, while the
          // Flutter wrapper uses this same completion-only path successfully.
          // Preserve the public event contract with explicit 0/1 boundaries;
          // completion is emitted only after local package validation.
          emitNativeProgress(flight, 0f)
          NosmaiCloud.download(
            flight.filterId,
            NosmaiCloud.DownloadCallback { callbackId, success, localPath, nativeError ->
              enqueueNativeDownloadCompletion(
                flight,
                callbackId,
                success,
                localPath,
                nativeError
              )
            }
          )
        } catch (error: Throwable) {
          finishDownload(
            flight,
            null,
            if (error is NosmaiCloudException) {
              error
            } else {
              NosmaiCloudException(
                NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
                "Unable to start the Nosmai cloud package download",
                error
              )
            }
          )
        }
      }
    } catch (error: RejectedExecutionException) {
      finishDownload(
        flight,
        null,
        NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
          "The Nosmai cloud worker is unavailable",
          error
        )
      )
    }
  }

  private fun enqueueNativeDownloadCompletion(
    flight: DownloadFlight,
    callbackId: String?,
    success: Boolean,
    localPath: String?,
    nativeError: String?
  ) {
    if (stopped.get()) return
    try {
      cloudExecutor.execute {
        if (!isActive(flight)) return@execute
        completeNativeDownload(flight, callbackId, success, localPath, nativeError)
      }
    } catch (error: RejectedExecutionException) {
      if (!stopped.get()) {
        finishDownload(
          flight,
          null,
          NosmaiCloudException(
            NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
            "The Nosmai cloud worker could not finalize the download",
            error
          )
        )
      }
    }
  }

  private fun completeNativeDownload(
    flight: DownloadFlight,
    callbackId: String?,
    success: Boolean,
    localPath: String?,
    nativeError: String?
  ) {
    if (callbackId?.trim() != flight.filterId) {
      finishDownload(
        flight,
        null,
        NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
          "The native download completed for an unexpected filter ID"
        )
      )
      return
    }

    if (!success) {
      val detail = nativeError?.trim()?.takeIf(String::isNotEmpty)
      finishDownload(
        flight,
        null,
        NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
          detail ?: "The Nosmai cloud package download failed"
        )
      )
      return
    }

    val packageFile = strictDownloadedPackage(localPath)
    if (packageFile == null) {
      finishDownload(
        flight,
        null,
        NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
          "The native download did not produce a readable absolute .nosmai package"
        )
      )
      return
    }

    resolvedDownloadPaths[flight.filterId] = packageFile.path
    emitTerminalProgress(flight)
    finishDownload(
      flight,
      downloadResult(flight.filterId, packageFile.path, false),
      null
    )
  }

  private fun emitNativeProgress(flight: DownloadFlight, rawProgress: Float) {
    if (!rawProgress.isFinite()) return
    val progress = when {
      rawProgress <= 0f -> 0.0
      rawProgress <= 1f -> rawProgress.toDouble()
      rawProgress <= 100f -> rawProgress.toDouble() / 100.0
      else -> 1.0
    }.coerceIn(0.0, 1.0)

    if (!isActiveForProgress(flight)) return
    dispatchToMain {
      if (!isActiveForProgress(flight)) return@dispatchToMain
      runCatching { delegate?.onCloudDownloadProgress(flight.filterId, progress) }
    }
  }

  private fun emitTerminalProgress(flight: DownloadFlight) {
    if (!isActiveForProgress(flight)) return
    dispatchToMain {
      // A successful completion removes the flight before this main-thread
      // event necessarily runs. Only shutdown should suppress the terminal
      // boundary once the package itself has been validated.
      if (stopped.get()) return@dispatchToMain
      runCatching { delegate?.onCloudDownloadProgress(flight.filterId, 1.0) }
    }
  }

  private fun onDownloadWatchdog(flight: DownloadFlight) {
    val callbacks = synchronized(downloadLock) {
      if (downloads[flight.filterId] !== flight || flight.timedOut) {
        emptyList()
      } else {
        flight.timedOut = true
        flight.callbacks.toList().also { flight.callbacks.clear() }
      }
    }
    val error = downloadTimeoutError(flight.filterId)
    callbacks.forEach { it.reject(error) }
    // Keep a tombstone in [downloads]. The native API has no cancellation
    // primitive, so another caller must not start a duplicate native download.
    // Its eventual callback removes the tombstone and refreshes the path cache.
  }

  private fun finishDownload(
    flight: DownloadFlight,
    result: Map<String, Any>?,
    error: NosmaiCloudException?
  ) {
    val callbacks = synchronized(downloadLock) {
      if (downloads[flight.filterId] !== flight) {
        emptyList()
      } else {
        downloads.remove(flight.filterId)
        flight.watchdog?.cancel(false)
        flight.callbacks.toList().also { flight.callbacks.clear() }
      }
    }
    callbacks.forEach { callback ->
      if (result != null) callback.resolve(result)
      else callback.reject(error ?: NosmaiCloudException(
        NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
        "The Nosmai cloud package download failed"
      ))
    }
  }

  private fun isActive(flight: DownloadFlight): Boolean = synchronized(downloadLock) {
    downloads[flight.filterId] === flight
  }

  private fun isActiveForProgress(flight: DownloadFlight): Boolean =
    !stopped.get() && synchronized(downloadLock) {
      downloads[flight.filterId] === flight && !flight.timedOut
    }

  private fun findStrictCachedPackage(filterId: String): File? {
    resolvedDownloadPaths[filterId]?.let { remembered ->
      strictDownloadedPackage(remembered)?.let { return it }
      resolvedDownloadPaths.remove(filterId)
    }

    val item = runCatching {
      NosmaiCloud.cachedList().firstOrNull { it.id?.trim() == filterId }
    }.getOrNull()
    val packageFile = strictDownloadedPackage(item?.localPath) ?: runCatching {
      NosmaiCloud.list().firstOrNull { it.id?.trim() == filterId }
    }.getOrNull()?.let { strictDownloadedPackage(it.localPath) } ?: return null
    resolvedDownloadPaths[filterId] = packageFile.path
    return packageFile
  }

  private fun resolveExactDownloadedPath(filterId: String): String? {
    resolvedDownloadPaths[filterId]?.let { return it }

    runCatching {
      NosmaiCloud.cachedList().firstOrNull { it.id?.trim() == filterId }
    }.getOrNull()?.localPath?.trim()?.takeIf(String::isNotEmpty)?.let { return it }

    // list() asks the public API to resolve the native path for this exact item.
    // It is intentionally not converted into a filename or directory scan.
    return runCatching {
      NosmaiCloud.list().firstOrNull { it.id?.trim() == filterId }
    }.getOrNull()?.localPath?.trim()?.takeIf(String::isNotEmpty)
  }

  private fun validateRemovalTarget(path: String): File {
    if (path.indexOf('\u0000') >= 0) {
      throw unsafeRemovalPath("The resolved cloud package path is invalid")
    }
    val unresolved = File(path)
    if (!unresolved.isAbsolute) {
      throw unsafeRemovalPath("The resolved cloud package path is not absolute")
    }
    val canonical = try {
      unresolved.canonicalFile
    } catch (error: Throwable) {
      throw NosmaiCloudException(
        NosmaiCloudErrorCode.CLOUD_REMOVE,
        "The resolved cloud package path could not be canonicalized",
        error
      )
    }
    if (!canonical.name.endsWith(NOSMAI_PACKAGE_EXTENSION, ignoreCase = true)) {
      throw unsafeRemovalPath("The resolved cloud package is not a .nosmai file")
    }

    val roots = listOf(appContext.filesDir, appContext.cacheDir).map { root ->
      try {
        root.canonicalFile
      } catch (error: Throwable) {
        throw NosmaiCloudException(
          NosmaiCloudErrorCode.CLOUD_REMOVE,
          "The application storage roots could not be canonicalized",
          error
        )
      }
    }
    if (roots.none { canonical.isStrictDescendantOf(it) }) {
      throw unsafeRemovalPath(
        "The resolved cloud package is outside the application's files and cache directories"
      )
    }
    if (canonical.isDirectory) {
      throw unsafeRemovalPath("A cloud package directory cannot be removed")
    }
    return canonical
  }

  private fun strictDownloadedPackage(path: String?): File? {
    val normalized = path?.trim()?.takeIf {
      it.isNotEmpty() && it.indexOf('\u0000') < 0
    } ?: return null
    val unresolved = File(normalized)
    if (!unresolved.isAbsolute) return null
    val canonical = runCatching { unresolved.canonicalFile }.getOrNull() ?: return null
    if (!canonical.name.endsWith(NOSMAI_PACKAGE_EXTENSION, ignoreCase = true)) return null
    if (!canonical.exists() || !canonical.isFile || !canonical.canRead()) return null
    if (canonical.length() <= 0L) return null
    return canonical
  }

  private fun File.isStrictDescendantOf(root: File): Boolean =
    path.startsWith(root.path + File.separator)

  private fun buildCatalogEnvelope(
    nativeItems: List<NosmaiCloud.Item>?,
    nativePagination: PaginationSnapshot,
    query: ValidatedQuery
  ): Map<String, Any> {
    val items = ArrayList<Map<String, Any>>()
    val seenIds = LinkedHashSet<String>()

    nativeItems.orEmpty().forEach { item ->
      val filterId = normalizedSafeFilterId(item.id) ?: return@forEach
      if (!seenIds.add(filterId)) return@forEach

      val packageFile = sequenceOf(
        item.localPath,
        resolvedDownloadPaths[filterId]
      ).mapNotNull(::strictDownloadedPackage).firstOrNull()
      if (packageFile != null) {
        resolvedDownloadPaths[filterId] = packageFile.path
      } else {
        resolvedDownloadPaths.remove(filterId)
      }

      val name = item.name?.trim()?.takeIf(String::isNotEmpty) ?: filterId
      val category = item.category?.trim().orEmpty()
      val packageType = query.outputPackageType ?: packageTypeForCategory(category)
      val output = LinkedHashMap<String, Any>()
      output["id"] = filterId
      output["filterId"] = filterId
      output["name"] = name
      output["displayName"] = name
      output["description"] = ""
      output["path"] = packageFile?.path.orEmpty()
      output["fileSize"] = packageFile?.length()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
      output["type"] = "cloud"
      output["filterType"] = packageType
      output["isFree"] = true
      output["isDownloaded"] = packageFile != null
      output["downloadCount"] = 0
      output["price"] = 0.0
      if (category.isNotEmpty()) output["category"] = category
      safePreviewUrl(item.thumbnailUrl)?.let { output["previewUrl"] = it }
      items += output
    }

    val currentPage = nativePagination.currentPage.coerceAtLeast(1)
    val totalPages = nativePagination.totalPages.coerceAtLeast(1)
    val totalItems = nativePagination.totalItems.coerceAtLeast(0)
    val itemsPerPage = nativePagination.itemsPerPage.takeIf { it > 0 } ?: query.limit
    val pagination = linkedMapOf<String, Any>(
      "currentPage" to currentPage,
      "totalPages" to totalPages,
      "totalItems" to totalItems,
      "itemsPerPage" to itemsPerPage,
      "hasNextPage" to nativePagination.hasNextPage,
      "hasPreviousPage" to nativePagination.hasPreviousPage
    )
    return linkedMapOf(
      "items" to items,
      "pagination" to pagination
    )
  }

  private fun validateQuery(query: NosmaiAndroidCloudQuery): ValidatedQuery {
    if (query.version.trim() != SUPPORTED_CLOUD_VERSION) {
      throw NosmaiCloudException(
        NosmaiCloudErrorCode.INVALID_ARGUMENT,
        "version must be $SUPPORTED_CLOUD_VERSION"
      )
    }
    if (query.page < 0) {
      throw NosmaiCloudException(
        NosmaiCloudErrorCode.INVALID_ARGUMENT,
        "page must be zero (omitted) or a positive integer"
      )
    }
    if (query.limit !in 1..100) {
      throw NosmaiCloudException(
        NosmaiCloudErrorCode.INVALID_ARGUMENT,
        "limit must be between 1 and 100"
      )
    }

    val outputType = query.packageType?.trim()?.lowercase(Locale.ROOT)
    val nativeType = when (outputType) {
      null -> null
      "effect" -> "effects"
      "filter" -> "filter"
      "background" -> "bg"
      "beauty_effect" -> "beauty_effect"
      "game" -> "games"
      else -> throw NosmaiCloudException(
        NosmaiCloudErrorCode.INVALID_ARGUMENT,
        "packageType must be filter, effect, background, beauty_effect, or game"
      )
    }
    return ValidatedQuery(
      nativePage = query.page.takeIf { it > 0 } ?: 1,
      limit = query.limit,
      nativeFilterType = nativeType,
      outputPackageType = outputType,
      fetchAllPages = query.fetchAllPages
    )
  }

  private fun packageTypeForCategory(category: String): String {
    return when (
      category.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
    ) {
      "effect", "effects", "special_effect", "special_effects" -> "effect"
      "background", "backgrounds", "bg" -> "background"
      "beauty", "beauty_effect", "beauty_effects", "makeup" -> "beauty_effect"
      "game", "games" -> "game"
      else -> "filter"
    }
  }

  private fun safePreviewUrl(value: String?): String? {
    val url = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (url.startsWith("data:", ignoreCase = true)) return null
    if (url.contains(";base64,", ignoreCase = true)) return null
    return url.takeIf {
      it.startsWith("https://", ignoreCase = true) ||
        it.startsWith("http://", ignoreCase = true)
    }
  }

  private fun normalizedSafeFilterId(value: String?): String? {
    val filterId = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (
      filterId == "." ||
      filterId == ".." ||
      filterId.contains("..") ||
      filterId.contains('/') ||
      filterId.contains('\\') ||
      filterId.any { it.code < 0x20 }
    ) {
      return null
    }
    return filterId
  }

  private fun downloadResult(
    filterId: String,
    path: String,
    alreadyDownloaded: Boolean
  ): Map<String, Any> = linkedMapOf(
    "filterId" to filterId,
    "path" to path,
    "alreadyDownloaded" to alreadyDownloaded
  )

  private fun <T> registerCompletion(
    callback: (T?, NosmaiCloudException?) -> Unit
  ): Completion<T> {
    val completion = Completion<T>(callback)
    val rejectNow = synchronized(callbackLock) {
      if (stopped.get()) true
      else {
        pendingCallbacks += completion
        false
      }
    }
    if (rejectNow) completion.reject(sessionDestroyedError())
    return completion
  }

  private fun <T> submit(
    completion: Completion<T>,
    fallbackCode: String,
    fallbackMessage: String,
    operation: () -> Unit
  ) {
    if (completion.isSettled()) return
    try {
      cloudExecutor.execute {
        if (stopped.get()) {
          completion.reject(sessionDestroyedError())
          return@execute
        }
        try {
          operation()
        } catch (error: NosmaiCloudException) {
          completion.reject(error)
        } catch (error: Throwable) {
          completion.reject(NosmaiCloudException(fallbackCode, fallbackMessage, error))
        }
      }
    } catch (error: RejectedExecutionException) {
      completion.reject(
        if (stopped.get()) sessionDestroyedError()
        else NosmaiCloudException(fallbackCode, fallbackMessage, error)
      )
    }
  }

  private fun dispatchToMain(action: () -> Unit) {
    val delivery = Runnable(action)
    if (Looper.myLooper() == mainHandler.looper) {
      delivery.run()
    } else if (!mainHandler.post(delivery)) {
      delivery.run()
    }
  }

  private fun unsafeRemovalPath(message: String) = NosmaiCloudException(
    NosmaiCloudErrorCode.CLOUD_REMOVE,
    message
  )

  private fun sessionDestroyedError() = NosmaiCloudException(
    NosmaiCloudErrorCode.SESSION_DESTROYED,
    "The Nosmai cloud helper has been shut down"
  )

  private fun downloadTimeoutError(filterId: String) = NosmaiCloudException(
    NosmaiCloudErrorCode.CLOUD_DOWNLOAD_TIMEOUT,
    "Timed out waiting for cloud package '$filterId'; the native download may still finish"
  )

  private companion object {
    const val DOWNLOAD_WATCHDOG_MILLIS = 315_000L
    const val NOSMAI_PACKAGE_EXTENSION = ".nosmai"
  }
}

private const val SUPPORTED_CLOUD_VERSION = "2.0.0"

private val ALL_CLOUD_NATIVE_TYPES = listOf(
  "effects",
  "filter",
  "bg",
  "beauty_effects",
  "games"
)

private val CLOUD_THREAD_COUNTER = AtomicInteger(0)

private fun namedDaemonThreadFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
  Thread(runnable, "$prefix-${CLOUD_THREAD_COUNTER.incrementAndGet()}").apply {
    isDaemon = true
  }
}
