package com.imooc.android.scopestorage.storage

import android.content.ContentUris
import android.content.Context
import android.media.audiofx.EnvironmentalReverb
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.os.EnvironmentCompat
import com.imooc.android.scopestorage.permission.AppGlobals
import com.imooc.android.scopestorage.permission.HiPermission
import com.imooc.android.scopestorage.storage.HiStorage.EXTERNAL_FILE_DIRECTORY
import java.io.File
import java.lang.Exception

internal object Util {

    /**
     * 根据媒体文件类型推断其合理存储的目录
     *
     * @param mimeType 媒体文件类型，比如(text/plain)
     * @return 媒体文件合理存储的目录
     */
    fun guessExternalFileRelativeDirectory(mimeType: String?): String {
        if (!isMediaMimeType(mimeType)) {
            return Environment.DIRECTORY_DOWNLOADS
        }
        return when {
            mimeType!!.startsWith("image/") -> {
                Environment.DIRECTORY_PICTURES
            }
            mimeType.startsWith("video/") -> {
                Environment.DIRECTORY_MOVIES
            }
            mimeType.startsWith("audio/") -> {
                Environment.DIRECTORY_PODCASTS
            }
            else -> {
                Environment.DIRECTORY_DOWNLOADS
            }
        }
    }

    /**
     * 根据媒体文件类型推断其合理存储的目录，比如（/storage/emulated/0/Pictures/）
     *
     * @param mimeType 媒体文件类型，比如(text/plain)
     * @return 媒体文件合理存储的目录
     */
    fun guessExternalFileDirectory(mimeType: String?): File {
        val relativePath = guessExternalFileRelativeDirectory(mimeType)
        return Environment.getExternalStoragePublicDirectory(relativePath)
    }

    /**
     * 根据媒体文件类型推断其合理存储的uri
     *
     * @param mimeType 媒体文件类型，比如(image/png)
     * @return 媒体文件合理存储的uri
     */
    fun guessExternalMediaUri(mimeType: String?): Uri {
        if (!isMediaMimeType(mimeType)) {
            return MediaStore.Files.getContentUri("external")
        }
        return when {
            mimeType!!.startsWith("image/") -> {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            mimeType.startsWith("video/") -> {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            mimeType.startsWith("audio/") -> {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            else -> MediaStore.Files.getContentUri("external")
        }
    }

    /**
     * 根据文件的名称(hello.txt)推断文件的类型(application/pdf)
     *
     * @param fileName (hello.txt)
     * @return  (application/pdf)
     */
    fun guessFileMimeType(fileName: String): String? {
        if (!fileName.contains(".")) {
            throw IllegalArgumentException("parameter $fileName is invalidate, Must have And Only one '.'")
        }
        if (fileName.split(".").size > 2) {
            throw IllegalArgumentException("parameter $fileName is invalidate, Only one '.' can exist")
        }
        val indexOfDot = fileName.lastIndexOf(".")
        val extension = fileName.substring(indexOfDot + 1)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /**
     * 判断一个文件是否不是媒体文件类型
     *
     * @param mimeType
     * @return
     */
    fun isMediaMimeType(mimeType: String?): Boolean {
        if (mimeType != null
            && (mimeType.startsWith("image/")
                    || mimeType.startsWith("video/")
                    || mimeType.startsWith("audio/"))
        ) {
            return true
        }
        return false
    }

    /**
     * 根据uri 查询它的mimeType
     *
     * @param context
     * @param uri 比如：content://media/external/file/10086
     * @return 比如:application/pdf
     */
    fun queryMimeTypeFromUri(context: Context, uri: Uri): String? {
        val id = ContentUris.parseId(uri)
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
            null
        )?.use {
            val columnIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            if (it.moveToFirst()) {
                return it.getString(columnIndex)
            }
            it.close()
        }
        return null
    }

    /**
     * 使用SAF 访问文件时，构建一个默认打开的目录，我们默认打开在download/目录
     * 如果imooc目录存在，那么默认打开的就是download/imooc目录
     *
     * URI 格式：content://com.android.externalstorage.documents/document/primary: + 相对路径的 urlencode
     * 例如：
     * 路径 /sdcard=content://com.android.externalstorage.documents/document/primary:
     * 路径 /sdcard/Download=content://com.android.externalstorage.documents/document/primary:Download
     * 路径 /sdcard/Download/Image=content://com.android.externalstorage.documents/document/primary:Download%2fImage
     *
     * @return
     */
    fun getExtraInitUri(): Uri {
        // 这个值没有定义成常量，只能谢在这里
        val baseUri = "content://com.android.externalstorage.documents/document/primary:"
        if (HiPermission.isGranted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            try {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), EXTERNAL_FILE_DIRECTORY)
                if (!file.exists()) {
                    file.mkdirs()
                }
                return Uri.parse(baseUri + Uri.encode(Environment.DIRECTORY_DOWNLOADS + "/" + EXTERNAL_FILE_DIRECTORY))
            } catch (ex: Exception) {
                // 如果文件创建失败了，则默认打开download目录
            }
        }
        return Uri.parse(baseUri + Uri.encode(Environment.DIRECTORY_DOWNLOADS))
    }

    /**
     * 获取自己应用的包名
     */
    fun getPackageName(): String {
        return AppGlobals.get()!!.packageName
    }
}