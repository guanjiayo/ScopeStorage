package com.imooc.android.scopestorage

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.imooc.android.scopestorage.permission.HiPermission
import com.imooc.android.scopestorage.storage.HiStorage
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream


class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }


    @SuppressLint("WrongThread")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        //-------------共享媒体目录文件操作-------------//
        action_write_shared_image.setOnClickListener {
            saveSharedMedia()
        }
        action_read_shared_images.setOnClickListener {
            querySharedMedias()
        }
        action_delete_media.setOnClickListener {
            deleteSharedMedia()
        }

        //-------------SAF 共享文档目录文件操作-------------//
        action_save_shared_document.setOnClickListener {
            saveSharedDocument()
        }
        action_read_document.setOnClickListener {
            readSharedDocument()
        }
        action_delete_document.setOnClickListener {
            deleteSharedDocument()
        }
    }

    private fun deleteSharedMedia() {
        HiPermission.permission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .callback(object : HiPermission.SimpleCallback {
                override fun onResult(result: Boolean) {
                    val uri = HiStorage.queryMedias(this@MainActivity)[0].uri
                    HiStorage.deleteFile(this@MainActivity, uri) {
                        Toast.makeText(this@MainActivity, "删除共享媒体文件结果:${it}", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "删除共享媒体文件结果:${it}")
                    }
                }
            }).request(this)
    }

    private fun querySharedMedias() {
        val permission = android.Manifest.permission.READ_EXTERNAL_STORAGE
        HiPermission.permission(permission).callback(object : HiPermission.SimpleCallback {
            override fun onResult(result: Boolean) {
                val loadImageMedias = HiStorage.queryMedias(this@MainActivity)
                Log.e(TAG, "共享媒体集查询结果:${loadImageMedias.size}")
                for (imageMedia in loadImageMedias) {
                    Log.d(TAG, imageMedia.toString())
                }
            }
        }).request(this)
    }

    private fun saveSharedMedia() {
        HiPermission.permission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .callback(object : HiPermission.SimpleCallback {
                override fun onResult(result: Boolean) {
                    if (result) {
                        val decodeBitmap = BitmapFactory.decodeResource(resources, R.drawable.android11)
                        val byteArrayOutputStream = ByteArrayOutputStream()
                        decodeBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                        val byteArray = byteArrayOutputStream.toByteArray()
                        val uri = HiStorage.saveMedia(
                            this@MainActivity,
                            byteArray,
                            "android-shared-image.png",
                            "image/png",
                            decodeBitmap.width,
                            decodeBitmap.height
                        )
                        if (uri != null) {
                            Toast.makeText(this@MainActivity, "共享目录图片保存成功", Toast.LENGTH_LONG).show()
                            Log.e(TAG, "共享目录图片保存成功: ${uri}")
                        }
                    }
                }
            }).request(this)
    }


    private fun saveSharedDocument() {
        HiPermission.permission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .callback(object : HiPermission.SimpleCallback {
                override fun onResult(result: Boolean) {
                    val byteArray = "1234567890".toByteArray()
                    HiStorage.createFile(this@MainActivity,
                        byteArray, "shared-document-text.txt", null) {
                        it?.let {
                            Toast.makeText(this@MainActivity, "外部文档目录文件保存成功", Toast.LENGTH_SHORT).show()
                            Log.e(TAG, "外部共享文档目录文件保存成功:$it")
                        }
                    }
                }
            }).request(this)
    }

    private fun readSharedDocument() {
        HiStorage.pickFile(this@MainActivity, "text/plain") {
            it?.apply {
                val content = String(contentResolver.openInputStream(it)?.readBytes()!!)
                Toast.makeText(this@MainActivity, "读取共享文档目录文件结果：${content}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "读取共享文档目录文件结果：${content}")
            }
        }
    }

    private fun deleteSharedDocument() {
        HiStorage.pickFile(this, "*/*") {
            it?.let {
                HiStorage.deleteFile(this, it) { ret ->
                    Toast.makeText(this@MainActivity, "删除选择的文档结果：${ret}", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "删除选择的文档结果：${ret}")
                }
            }
        }
    }
}