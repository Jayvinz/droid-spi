package com.example.spi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 生成类的父类。系统按 manifest 里的 `<provider android:name="...">` 在 **Application.onCreate 之前**
 * 实例化它，因此 provider 配置表在业务代码第一次调用 `ServiceLoader.load()` 时已经就绪。
 *
 * 这里刻意不用 `Class.forName` 去加载生成类：入口由 Android 框架按 manifest 声明反射创建，
 * 我们的代码里没有任何按类名查找。
 */
abstract class ServiceLoaderInitializer : ContentProvider() {

    protected abstract fun createConfiguration(): ProviderConfiguration

    final override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ServiceLoader.initialize(ctx, createConfiguration())
        return true
    }

    final override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null

    final override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    final override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
