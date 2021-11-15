package com.imooc.android.scopestorage.storage

import android.annotation.SuppressLint
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.imooc.android.scopestorage.permission.AppGlobals
import com.imooc.android.scopestorage.permission.HiPermission
import java.io.File
import java.io.FileOutputStream


object HiStorage {
    /**
     * 未启用分区存储的时候，媒体文件存储的位置
     */
    const val EXTERNAL_FILE_DIRECTORY = "imooc"

    /**
     * 外部存储共享存储空间非媒体文件数据库的uri，其保存在/Download目录下
     */
    private val DOCUMENT_EXTERNAL_URI: Uri = MediaStore.Files.getContentUri("external")

    /**
     * 使用MediaStore API 保存媒体文件(音频，视频，图片)到媒体共享目录(pictures/,movies/,audio/)
     * 启用分区存储:
     *     此方法不需要权限，适用于全版本
     * 未启用分区存储:
     *     需要先申请获取{WRITE_EXTERNAL_STORAGE}
     *
     * @param activity 上下文对象
     * @param byteArray 待写入媒体文件的数据
     * @param fileName 媒体文件名称,比如(hello.txt)
     * @param mimeType 媒体文件的类型，比如(text/plain),如果为空则根据{@param fileName}自动推断
     * @param width 媒体文件的宽(图片、视频)
     * @param height 媒体文件的高(图片、视频)
     * @return 文件的uri
     */
    @SuppressLint("InlinedApi")
    fun saveMedia(
        context: Context, byteArray: ByteArray, fileName: String, mimeType: String?,
        width: Int,
        height: Int
    ): Uri? {
        // 1. 自动推断媒体文件的类型
        val mediaMimeType = mimeType ?: Util.guessFileMimeType(fileName)

        // 2. 推断出媒体文件应该插入的数据库的uri
        val externalMediaUri = Util.guessExternalMediaUri(mimeType)

        // 3. 分区存储未被启用，即使用运行时动态权限模式,跟Android10之前的文件读写一样
        if (isExternalStorageLegacy()) {
            // 4. 构建文件存储路径    /storage/emulated/0/Pictures/
            val externalMediaDir = Util.guessExternalFileDirectory(mediaMimeType)
            //    构建文件存储的子目录 /storage/emulated/0/Pictures/imooc
            val externalMediaAppDir = File(externalMediaDir, EXTERNAL_FILE_DIRECTORY)
            if (!externalMediaAppDir.exists()) {
                externalMediaAppDir.mkdirs()
            }
            // 5. 文件流写入
            val mediaFile = File(externalMediaAppDir, fileName)
            try {
                val outputStream = FileOutputStream(mediaFile)
                outputStream.write(byteArray)
                outputStream.flush()
                outputStream.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
                mediaFile.delete()
                return null
            }

            // 6. 插入相册
            val values = ContentValues()
            // 文件绝对路径
            values.put(MediaStore.MediaColumns.DATA, mediaFile.absolutePath)
            // 文件名称
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            // 文件类型，如果传递的为null, 则自动根据文件名的后缀去推断
            values.put(MediaStore.MediaColumns.MIME_TYPE, mediaMimeType)
            // 对于图片，视频媒体文件，可以选择把宽高信息也存储起来
            values.put(MediaStore.MediaColumns.WIDTH, width)
            values.put(MediaStore.MediaColumns.HEIGHT, height)
            // 根据文件类型，插入图片或视频或音频的数据库中
            return context.contentResolver.insert(externalMediaUri, values)
        } else {
            val values = ContentValues()
            // 文件名称
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            // 文件类型，如果传递的为null, 则自动根据文件名的后缀去推断
            values.put(MediaStore.MediaColumns.MIME_TYPE, mediaMimeType)
            // 对于图片，视频媒体文件，可以选择把宽高信息也存储起来
            values.put(MediaStore.MediaColumns.WIDTH, width)
            values.put(MediaStore.MediaColumns.HEIGHT, height)
            // 获取独占访问权限，在文件写入成功之前，其它应用不可对此文件进行访问，操作
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)

            // 1. 根据文件的类型，去推断是应该插入Image 表，还是Movies 表，还是Audio表
            val uri = context.contentResolver.insert(externalMediaUri, values) ?: return null
            // 得到创建的空文件的uri 则获取输出流，将bytes数据写入
            val openOutputStream = context.contentResolver.openOutputStream(uri) ?: return null
            openOutputStream.write(byteArray)
            openOutputStream.flush()
            openOutputStream.close()

            values.clear()
            // 更新文件状态，此时其它应用程序可以访问的到
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            // 很奇怪，在Android 11上自己创建的文件，有的时候也是没权限去操作它的
            // 在这里可以授予自己修改该文件的权限。在{deleteFile}的时候就不会弹窗了
            grantUriPermission(context, uri)
            return uri
        }
    }

    /**
     * 使用MediaStore API 查询外部共享存储空间的媒体文件(音频，视频，图片)。
     * 已启用分区存储:
     * ————————如果已获得{@READ_EXTERNAL_STORAGE}权限，则可以查询设备上所有媒体文件。但私有目录、非媒体目录下存放的媒体文件查询不到
     * ————————如果没有获得{READ_EXTERNAL_STORAGE}权限，则只能查询到属于自己应用的媒体文件
     * 未启用分区存储:
     * ————————需要先申请获取{@READ_EXTERNAL_STORAGE}权限
     * 此方法适用于全版本
     * a. 如果{@param displayName }不为空，则返回文件名称为{@param displayName }的媒体信息
     * b. 如果{@param mimeType }不为空，则在指定的文件类型中去查询文件名为{@param displayName}的媒体信息
     * c. 如果{@param displayName }，{@param mimeType }同时为空，则查询所有媒体文件
     * @param activity 上下文对象
     * @param displayName 待查询的文件名称，比如(hello.txt)
     * @param mimeType 待查询的文件的类型，比如(text/plain)
     * @return 查询到的媒体文件信息
     */
    fun queryMedias(
        activity: Activity, displayName: String? = null, mimeType: String? = null
    ): List<MediaInfo> {
        val list = mutableListOf<MediaInfo>()
        // 查询语句的where 条件
        var selection = ""
        // 查询语句where条件中的占位符参数值
        var sectionArgs = arrayOfNulls<String>(0)
        // 1. 构建查询的列名集合
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.MIME_TYPE
        )
        // 2. 如果查询的文件名不为空，则全匹配查询
        if (!TextUtils.isEmpty(displayName)) {
            selection += "${MediaStore.MediaColumns.DISPLAY_NAME} = ? "
            sectionArgs += displayName
        }
        // 3. 如果文件类型不为空，则半匹配查询
        if (!TextUtils.isEmpty(mimeType)) {
            selection += "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?"
            sectionArgs += mimeType
        }
        // 4. 如果文件类型未指定，则查询image、video、audio所有媒体文件
        if (TextUtils.isEmpty(mimeType)) {
            selection += "${MediaStore.MediaColumns.MIME_TYPE} LIKE ? " +
                    "or ${MediaStore.MediaColumns.MIME_TYPE} LIKE ? " +
                    "or ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?"
            sectionArgs += arrayOf("image%", "video%", "audio%")
        }
        // 5. 查询结果的排序方式，以ID 倒叙排
        val sortOrder = "${MediaStore.Images.ImageColumns._ID} DESC"
        // 6. 推断要查询的媒体文件的uri
        //    如果查询所有媒体文件，则uri格式为content://media/external/file/
        val mediaExternalUri = Util.guessExternalMediaUri(mimeType)
        activity.contentResolver.query(
            mediaExternalUri,
            projection, selection, sectionArgs, sortOrder
        )?.use {
            while (it.moveToNext()) {
                val idIndex = it.getColumnIndex(MediaStore.MediaColumns._ID)
                val pathIndex = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val displayNameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val widthIndex = it.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIndex = it.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val mimeTypeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                //图片类型的uri格式：content://media/external/images/media/
                //视频类型的uri格式：content://media/external/video/media/
                val externalMediaUri = Util.guessExternalMediaUri(it.getString(mimeTypeIndex))
                val media = MediaInfo(
                    //content://media/external/video/media/21
                    ContentUris.withAppendedId(externalMediaUri, it.getLong(idIndex)),
                    it.getString(displayNameIndex),
                    it.getString(mimeTypeIndex),
                    it.getInt(widthIndex),
                    it.getInt(heightIndex),
                    it.getString(pathIndex)
                )

                list.add(media)
            }
            it.close()
        }

        return list
    }


    /**
     * 使用MediaStore API 删除文件(图片，视频，音频，文档).
     * 此方法适用于全版本
     * 如果APP targetSDKVersion>29且设备版本>Android10且开启了分区存储，删除不属于自己应用的文件会弹框请求用户授权
     * 如果APP targetSDKVersion<29或设备版本<Android10或未开启分区存储，删除文件需要申请运行时权限{@see android.permission.WRITE_EXTERNAL_STORAGE}
     *
     * @param activity 上下文对象
     * @param uri 待删除的文件的uri
     * @param callback 用于接收文件删除的结果
     */
    fun deleteFile(activity: FragmentActivity, uri: Uri, callback: (Boolean) -> Unit) {
        // 如果是通过SAF选择的文件/download，/document目录的文档文件【pdf,txt,png】
        // 其uri格式为content://com.android.providers.media.documents/primary:Pictures/android-shared-image.png
        if (DocumentsContract.isDocumentUri(activity, uri)) {
            val ret = DocumentsContract.deleteDocument(activity.contentResolver, uri)
            callback(ret)
            return
        }
        // 如果通过MediaStore api 查询得到的文件，
        // 其uri 格式为content://media/external/file/10086
        // 根据文件的uri 推断其类型
        val mimeType = Util.queryMimeTypeFromUri(activity, uri)
        // 判断是不是媒体文件类型
        val isMediaFile = Util.isMediaMimeType(mimeType)

        // 是否有权限删除它
        val hasPermission = activity.checkUriPermission(
            uri,
            Binder.getCallingPid(),
            Binder.getCallingUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        when {
            // android 11上如果没有权限删除该文件，我们才调用createDeleteRequest。才会弹窗
            // 删除的是自己应用的文件，不需要弹窗申请
            //【是媒体文件】且【没有权限删除它】且 【android 11的系统】且 【开启分区存储】且【是媒体文件】
            isMediaFile && !hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isExternalStorageLegacy() -> {
                // android11上 新增批量操作媒体文件的API
                val pendingIntent = MediaStore.createDeleteRequest(
                    activity.contentResolver,
                    arrayListOf(uri)
                )
                // 弹窗向用户申请删除文件的权限
                // activity.startIntentSender(pendingIntent.intentSender,null,0,0,0)
                HiPermission.startIntentSenderForResult(activity,
                    pendingIntent.intentSender,
                    object : HiPermission.SimpleCallback {
                        override fun onResult(result: Boolean) {
                            callback(result)
                        }
                    })
            }
            // 这里不需要判断有木有删除该文件的权限，因为没有权限的话，会走到catch里面，有权限的话直接就删掉了
            // 【是媒体文件】且【android 10系统】且 【开启分区存储】
            isMediaFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isExternalStorageLegacy() -> {
                try {
                    val delete = activity.contentResolver.delete(uri, null, null)
                    callback(delete > 0)
                } catch (ex: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ex is RecoverableSecurityException) {
                        // 捕获异常，如果是RecoverableSecurityException，则弹窗向用户申请授权
                        // activity.startIntentSender(pendingIntent.intentSender,null,0,0,0)
                        HiPermission.startIntentSenderForResult(activity,
                            ex.userAction.actionIntent.intentSender,
                            object : HiPermission.SimpleCallback {
                                override fun onResult(result: Boolean) {
                                    callback(result)
                                }
                            })
                        return
                    } else {
                        ex.printStackTrace()
                        callback(false)
                    }
                }
            }
            else -> {
                // android 10以前需要申请运行时的write_external_storage权限
                val delete = activity.contentResolver.delete(uri, null, null)
                callback(delete > 0)
            }
        }
    }


    /**
     * 使用SAF创建一个文件(image,video,pdf，txt,apk,word,excel...)，创建成功后返回文件的uri
     * 此方法不需要权限，适用于全版本
     * @param activity 上下文对象
     * @param byteArray 待写入文件的数据
     * @param fileName 待创建文件的名称,比如(hello.txt)
     * @param mimeType 待创建文件的类型,比如(text/plain), 如果为空，则根据{@link fileName}自动推断
     * @param callback 文件创建成功后文件的uri会回调给用户，进而可以写入数据。如果文件创建失败，则uri为空
     */
    fun createFile(
        activity: FragmentActivity, byteArray: ByteArray, fileName: String,
        mimeType: String?,
        callback: (Uri?) -> Unit
    ) {
        // 推断可能的文件的类型application/pdf
        val fileMimeType = mimeType ?: Util.guessFileMimeType(fileName)
        // 构建个隐士意图，启动SAF文件选择器
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = fileMimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            // android 8.0上指定默认打开的目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Util.getExtraInitUri())
            }
        }
        HiPermission.startActivityForResult(activity, intent,
            object : HiPermission.ActivityResultCallback {
                override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                    // 文件创建成功之后，文件的uri会回调在这里，紧接着就可以写入数据了
                    if (resultCode == Activity.RESULT_OK && data != null && data.data != null) {
                        try {
                            // 授予修改此文件的权限,在Android 11上{deleteFile}就不会弹窗了
                            grantUriPermission(activity, data.data!!)

                            activity.contentResolver.openOutputStream(data.data!!)?.use {
                                it.write(byteArray)
                                it.flush()
                                it.close()
                                callback(data.data)
                            }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            })
    }

    /**
     * 使用SAF选择文件(图片，视频，音频，文档)
     * 此方法不需要权限，适用于全版本
     *
     * @param activity 上下文件对象
     * @param mimeType 想要选择的文件的类型，比如(text/plain)
     * @param callback 用户选择文件后用于接收文件的uri的回调, 接着可以读取，写入，删除文件
     */
    fun pickFile(activity: FragmentActivity, mimeType: String?, callback: (Uri?) -> Unit) {
        // 构建隐示意图打开文件选择器
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // android 10+需要授予此权限，才能对文件进行访问操作
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // 要选择的文件的类型
        intent.type = mimeType ?: "*/*"
        // android 8.0上指定默认打开的目录
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Util.getExtraInitUri())
        }
        HiPermission.startActivityForResult(activity, intent,
            object : HiPermission.ActivityResultCallback {
                override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                    // 文件选择后，将回调在这里
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        callback(data.data)
                    } else {
                        callback(null)
                    }
                }
            })
    }

    /**
     * 使用MediaStore API 保存一个非媒体文件(pdf，txt,apk,word,excel...)到外部存储，返回文件保存成功后的uri。文件将存储在/Download/imooc目
     * 如果已开启分区存储，则不需要权限
     * 如果未启用分区存储，则需要先申请获得{ WRITE_EXTERNAL_STORAGE}
     * @param context 上下文对象
     * @param byteArray 待写入文件的数据
     * @param fileName 待创建文件的名称,比如(hello.txt)
     * @param mimeType 待创建文件的类型,比如(text/plain), 如果为空，则根据{@link fileName}自动推断
     * @return 文件创建成功后文件的uri会回调给用户，进而可以写入数据。如果文件创建失败，则uri为空
     */
    @SuppressLint("InlinedApi")
    fun saveDocument(
        context: Context,
        byteArray: ByteArray,
        fileName: String,
        mimeType: String? = null
    ): Uri? {
        // 自动推断文档文件的类型
        val docMimeType = mimeType ?: Util.guessFileMimeType(fileName)
        // 推断出文档发文件应该插入的数据库的uri
        val docExternalUri = Util.guessExternalMediaUri(docMimeType)
        // 在使用MediaStore API存储文件时判断一把，媒体文件应该存放在共享媒体目录或应用私有目录
        if (Util.isMediaMimeType(docMimeType)) {
            throw IllegalArgumentException("Media files should be stored in a shared media directory or application private directory")
        }

        // 分区存储未被启用，使用兼容存储模式，即运行时存储动态权限模式
        if (isExternalStorageLegacy()) {
            // /storage/0/Document
            val externalDocDir = Util.guessExternalFileDirectory(docMimeType)
            // /storage/emulated/0/Document/imooc
            val externalDocAppDir = File(externalDocDir, EXTERNAL_FILE_DIRECTORY)
            if (!externalDocAppDir.exists()) {
                externalDocAppDir.mkdirs()
            }
            val docFile = File(externalDocAppDir, fileName)
            try {
                val outputStream = FileOutputStream(docFile)
                outputStream.write(byteArray)
                outputStream.flush()
                outputStream.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
                docFile.delete()
                return null
            }

            // 插入文档文件的数据库
            val values = ContentValues()
            // 文件绝对路径
            values.put(MediaStore.Files.FileColumns.DATA, docFile.absolutePath)
            // 文件名称
            values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            // 文件类型，如果传递的为null, 则自动根据文件名的后缀去推断
            values.put(MediaStore.Files.FileColumns.MIME_TYPE, docMimeType)
            // 根据文件类型，插入文档文件数据库中
            return context.contentResolver.insert(docExternalUri, values)
        } else {
            val values = ContentValues()
            values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            values.put(MediaStore.Files.FileColumns.MIME_TYPE, docMimeType)
            // 获取独占访问权限，在文件写入成功之前，其它应用不可对此文件进行访问，操作
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)

            val uri = context.contentResolver.insert(DOCUMENT_EXTERNAL_URI, values) ?: return null
            val openOutputStream = context.contentResolver.openOutputStream(uri) ?: return null
            openOutputStream.write(byteArray)
            openOutputStream.flush()
            openOutputStream.close()

            values.clear()
            // 写入成功之后，更新文件的状态
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            // 授予自己修改此文件的权限
            grantUriPermission(context, uri)
            return uri
        }
    }

    /**
     * 使用MediaStore API查询所有符合条件的文档文件(不包含图片，音频，视频)
     * 自己应用创建的文件有操作权限，对非自己应用创建的文件没有操作权限
     * 开启分区存储后，只能查询到自己应用创建的文档文件
     *
     * @param activity 上下文对象
     * @param displayName 查询的文件的名称
     * @param mimeType 查询的文件的类型
     * @return
     */
    fun queryDocument(
        activity: Activity,
        displayName: String? = null,
        mimeType: String?
    ): List<MediaInfo> {
        val list = mutableListOf<MediaInfo>()
        // 查询语句的where 条件
        var selection = ""
        // 查询语句where条件中的占位符参数值
        var sectionArgs = arrayOfNulls<String>(0)
        // 构建查询的列名集合
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT, MediaStore.Files.FileColumns.MIME_TYPE
        )
        // 如果查询的文件名不为空，则全匹配查询
        if (!TextUtils.isEmpty(displayName)) {
            selection += "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? "
            sectionArgs += displayName
        }
        // 如果文件类型不为空，则半匹配查询
        if (!TextUtils.isEmpty(mimeType)) {
            selection += "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
            sectionArgs += mimeType
        }
        // 如果文件类型未指定，则查询除了媒体文件之外的所有文档文件
        if (TextUtils.isEmpty(mimeType)) {
            selection += "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE ?"
            sectionArgs += arrayOf("image%", "video%", "audio%")
        }
        // 查询结果的排序方式，以ID 倒叙排
        val sortOrder = "${MediaStore.Files.FileColumns._ID} DESC"
        activity.contentResolver.query(
            DOCUMENT_EXTERNAL_URI, projection, selection, sectionArgs, sortOrder
        )?.use {
            while (it.moveToNext()) {
                val idIndex = it.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val pathIndex = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val displayNameIndex = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val widthIndex = it.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
                val heightIndex = it.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
                val mimeTypeIndex = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                val media = MediaInfo(
                    ContentUris.withAppendedId(DOCUMENT_EXTERNAL_URI, it.getLong(idIndex)),
                    it.getString(displayNameIndex),
                    it.getString(mimeTypeIndex),
                    it.getInt(widthIndex),
                    it.getInt(heightIndex),
                    it.getString(pathIndex)
                )

                list.add(media)
            }
            it.close()
        }

        return list
    }

    /**
     * 授予自己应用修改该文件的权限，这样在android 11上我们删除自己应用创建文件的时，就不用弹窗了
     */
    private fun grantUriPermission(activity: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.grantUriPermission(
                Util.getPackageName(),
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /**
     * Android 10规定了APP有两种外部存储空间视图模式：Legacy View、Filtered View。
     *
     * Filtered View:App可以直接访问App-specific目录，但不能直接访问App-specific外的文件。
     *               访问公共目录或其他APP的App-specific目录，只能通过MediaStore、SAF、或者其他APP提供的ContentProvider、FileProvider等访问。
     * Legacy View:兼容模式。与Android 10以前一样，申请权限后App可访问外部存储，拥有完整的访问权限。
     *
     * @return true: 分区存储已启用
     */
    private fun isExternalStorageLegacy(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // return true:未开启
            return Environment.isExternalStorageLegacy()
        }

        return true
    }

    data class MediaInfo(
        val uri: Uri,
        val displayName: String,
        val mineType: String,
        val width: Int,
        val height: Int,
        val path: String
    )
}