package com.imooc.android.scopestorage.permission

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.EXTRA
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.TYPE
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.TYPE_CREATE_MEDIA_REQUEST
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.TYPE_DRAW_OVERLAYS
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.TYPE_RUNTIME_PERMISSION
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.TYPE_START_ACTIVITY_FOR_RESULT
import com.imooc.android.scopestorage.permission.HiPermission.PermissionFragment.Companion.TYPE_WRITE_SETTINGS
import java.util.*

class HiPermission private constructor(vararg permissions: String) {
    private var mOnRationaleListener: OnRationaleListener? = null
    private var mSimpleCallback: SimpleCallback? = null
    private var mFullCallback: FullCallback? = null

    private val mPermissions = LinkedHashSet<String>()
    private var mPermissionsRequest: MutableList<String> = mutableListOf()
    private var mPermissionsGranted: MutableList<String> = mutableListOf()
    private var mPermissionsDenied: MutableList<String> = mutableListOf()
    private var mPermissionsDeniedForever: MutableList<String> = mutableListOf()

    init {
        for (@PermissionConstants.Permission permission in permissions) {
            //这里把你申请的权限中的权限组 转换成数组，检查单个权限是否在manifest中定义  做到权限申请最小化。
            //比如 你申请了一个Manifest.permission_group.STORAGE权限组，但是manifest却只定义了Manifest.permission.READ_EXTERNAL_STORAGE。
            // 如果拿着权限组去申请，会失败的。所以此时就只去申请Manifest.permission.READ_EXTERNAL_STORAGE
            for (singlePermission in PermissionConstants.getPermissions(permission)) {
                //你申请的权限 必须在manifest 里面 已经定义了的才行
                if (MANIFEST_PERMISSIONS.contains(singlePermission)) {
                    mPermissions.add(singlePermission)
                }
            }
        }
        sInstance = this
    }

    /**
     * Set rationale listener.
     *
     *
     * 如果被永远拒绝了 ，弹框解释为什么申请权限的回调
     */
    fun rationale(listener: OnRationaleListener?): HiPermission {
        mOnRationaleListener = listener
        return this
    }

    /**
     * Set the simple call back.
     *
     *
     * 简单回调，只会告诉你已授权 还是未授权，不区分那个权限
     */
    fun callback(callback: SimpleCallback?): HiPermission {
        mSimpleCallback = callback
        return this
    }

    /**
     * Set the full call back.
     *
     *
     * 这个会回调 那些已授权，那些未授权，跟上面那个SimpleCallback看场景使用
     */
    fun callback(callback: FullCallback?): HiPermission {
        mFullCallback = callback
        return this
    }

    /**
     * 开始申请权限 了
     * Start request permission.
     */
    fun request(activity: FragmentActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mPermissionsGranted.addAll(mPermissions)
            requestCallback()
        } else {
            for (permission in mPermissions) {
                if (isGranted(permission)) {
                    mPermissionsGranted.add(permission)
                } else {
                    mPermissionsRequest.add(permission)
                }
            }
            if (mPermissionsRequest.isEmpty()) {
                requestCallback()
            } else {
                startPermissionFragment(activity)
            }
        }
    }


    private fun startPermissionFragment(activity: FragmentActivity) {
        mPermissionsDenied = ArrayList()
        mPermissionsDeniedForever = ArrayList()
        val bundle = bundleOf(Pair(TYPE, TYPE_RUNTIME_PERMISSION))
        PermissionFragment.start(
            activity,
            bundle,
            permissionCallback = object : PermissionResultCallback {
                override fun onRequestPermissionsResult(
                    requestCode: Int,
                    permissions: Array<String>,
                    grantResults: IntArray
                ) {
                    getPermissionsStatus(activity)
                    requestCallback()
                }
            })
    }


    private fun rationale(activity: FragmentActivity): Boolean {
        var isRationale = false
        if (mOnRationaleListener != null) {
            for (permission in mPermissionsRequest) {
                if (shouldShowRequestPermissionRationale(activity, permission)) {
                    getPermissionsStatus(activity)
                    mOnRationaleListener!!.rationale(object : OnRationaleListener.ShouldRequest {
                        override fun again(again: Boolean) {
                            if (again) {
                                startPermissionFragment(activity)
                            } else {
                                requestCallback()
                            }
                        }
                    })
                    isRationale = true
                    break
                }
            }
            mOnRationaleListener = null
        }
        return isRationale
    }

    private fun getPermissionsStatus(activity: Activity) {
        for (permission in mPermissionsRequest) {
            if (isGranted(permission)) {
                mPermissionsGranted.add(permission)
            } else {
                mPermissionsDenied.add(permission)
                if (!shouldShowRequestPermissionRationale(activity, permission)) {
                    mPermissionsDeniedForever.add(permission)
                }
            }
        }
    }

    private fun requestCallback() {
        if (mSimpleCallback != null) {
            //只有当所有权限都被授权的时候才会回调onGranted，否则只会把被拒绝的权限回调到onDenied
            //你也可以改成 把本次授权的回调到onGranted，被拒绝的回调到onDenied
            if (mPermissionsRequest.size == 0 || mPermissions.size == mPermissionsGranted.size
            ) {
                mSimpleCallback!!.onResult(true)
            } else {
                if (mPermissionsDenied.isNotEmpty()) {
                    mSimpleCallback!!.onResult(false)
                }
            }
            mSimpleCallback = null
        }

        if (mFullCallback != null) {
            //只有当所有权限都被授权的时候才会回调onGranted，否则只会把被拒绝的权限回调到onDenied
            //你也可以改成 把本次授权的回调到onGranted，被拒绝的回调到onDenied
            //但我们认为现在这种比较好，可以做到一次权限申请最小化，不要在首次启动一次性申请那么多，按需申请
            if (mPermissionsRequest.size == 0 || mPermissions.size == mPermissionsGranted.size
            ) {
                mFullCallback!!.onGranted(mPermissionsGranted)
            } else {
                if (mPermissionsDenied.isNotEmpty()) {
                    mFullCallback!!.onDenied(mPermissionsDeniedForever, mPermissionsDenied)
                }
            }
            mFullCallback = null
        }
        mOnRationaleListener = null
    }


    class PermissionFragment : Fragment() {
        companion object {
            const val EXTRA = "extra"
            const val TYPE = "type"
            private const val TAG = "PermissionFragment"
            private var mPermissionCallback: PermissionResultCallback? = null
            private var mActivityCallback: ActivityResultCallback? = null
            const val TYPE_RUNTIME_PERMISSION = 0x01
            const val TYPE_WRITE_SETTINGS = 0x02
            const val TYPE_DRAW_OVERLAYS = 0x03
            const val TYPE_CREATE_MEDIA_REQUEST = 0x04
            const val TYPE_START_ACTIVITY_FOR_RESULT = 0x05

            internal fun start(
                activity: FragmentActivity,
                arguments: Bundle,
                permissionCallback: PermissionResultCallback? = null,
                activityCallback: ActivityResultCallback? = null
            ) {
                this.mPermissionCallback = permissionCallback
                this.mActivityCallback = activityCallback
                val fm = activity.supportFragmentManager
                if (!fm.isStateSaved) {
                    var fragment = fm.findFragmentByTag(TAG) as? PermissionFragment
                    if (fragment == null) {
                        fragment = PermissionFragment()
                        fm.beginTransaction().add(android.R.id.content, fragment, TAG)
                            .hide(fragment)
                            .commitNowAllowingStateLoss()
                    }
                    fragment.start(arguments)
                }
            }
        }

        fun start(arguments: Bundle) {
            when (arguments.getInt(TYPE)) {
                TYPE_RUNTIME_PERMISSION -> {
                    //申请权限之前 如果需要需要弹窗说明,则这里暂停
                    if (sInstance!!.rationale(activity!!)) {
                        return
                    }
                    requestPermissions(sInstance!!.mPermissionsRequest.toTypedArray(), 1)
                }
                TYPE_WRITE_SETTINGS -> {
                    startWriteSettingsActivity()
                }
                TYPE_DRAW_OVERLAYS -> {
                    startOverlayPermissionActivity()
                }
                TYPE_CREATE_MEDIA_REQUEST -> {
                    val intentSender = arguments.getParcelable<IntentSender>(EXTRA)
                    startIntentSenderForResult(
                        intentSender,
                        TYPE_CREATE_MEDIA_REQUEST,
                        null,
                        0,
                        0,
                        0, Bundle.EMPTY
                    )
                }
                TYPE_START_ACTIVITY_FOR_RESULT -> {
                    val intent = arguments.getParcelable(EXTRA) as? Intent
                    startActivityForResult(intent, TYPE_START_ACTIVITY_FOR_RESULT)
                }
            }
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
        ) {
            mPermissionCallback?.onRequestPermissionsResult(requestCode, permissions, grantResults)
            mPermissionCallback = null
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            mActivityCallback?.onActivityResult(requestCode, resultCode, data)
            mActivityCallback = null
        }

        private fun startWriteSettingsActivity() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return
            }
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:" + sApplication.packageName)
            if (!isIntentAvailable(intent)) {
                launchAppDetailsSettings()
                return
            }
            startActivityForResult(intent, TYPE_WRITE_SETTINGS)
        }

        private fun startOverlayPermissionActivity() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:" + sApplication.packageName)
            if (!isIntentAvailable(intent)) {
                launchAppDetailsSettings()
                return
            }
            startActivityForResult(intent, TYPE_DRAW_OVERLAYS)
        }
    }


    interface OnRationaleListener {
        fun rationale(shouldRequest: ShouldRequest?)
        interface ShouldRequest {
            fun again(again: Boolean)
        }
    }

    interface SimpleCallback {
        fun onResult(result: Boolean)
    }

    interface ActivityResultCallback {
        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    }

    internal interface PermissionResultCallback {
        fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
        )
    }

    interface FullCallback {
        fun onGranted(permissionsGranted: List<String>?)
        fun onDenied(
            permissionsDeniedForever: List<String>?,
            permissionsDenied: List<String>?
        )
    }

    companion object {
        private var sInstance: HiPermission? = null
        private val sApplication = AppGlobals.get()!!
        private val MANIFEST_PERMISSIONS = getPermissions(sApplication.packageName)

        /**
         * 获取安装包在manifest中声明的权限集合--此时你可以在APP中 开发一个隐私权限页，
         * 告诉用户那些已授权，是干什么用的
         *
         * @param packageName
         * @return
         */
        @JvmStatic
        fun getPermissions(packageName: String): List<String> {
            val pm =
                sApplication.packageManager
            return try {
                val permissions = (pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS
                ).requestedPermissions ?: return emptyList())
                listOf(*permissions)
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
                emptyList()
            }
        }

        /**
         * 是否已经授权过了
         *
         * @param permissions
         * @return
         */
        fun isGranted(vararg permissions: String): Boolean {
            for (permission in permissions) {
                if (!isGranted(permission)) {
                    return false
                }
            }
            return true
        }

        private fun isGranted(permission: String): Boolean {
            return (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(sApplication, permission)
                    || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && android.Manifest.permission.WRITE_EXTERNAL_STORAGE == permission && !Environment.isExternalStorageLegacy())
        }

        /**
         * 检查APP是否获取修改系统设置的权限
         */
        @get:RequiresApi(api = Build.VERSION_CODES.M)
        val isGrantedWriteSettings: Boolean
            get() = Settings.System.canWrite(sApplication)

        @RequiresApi(api = Build.VERSION_CODES.M)
        fun requestWriteSettings(activity: FragmentActivity, callback: SimpleCallback?) {
            if (isGrantedWriteSettings) {
                callback?.onResult(true)
                return
            }
            val bundle = bundleOf(Pair(TYPE, TYPE_WRITE_SETTINGS))
            PermissionFragment.start(
                activity,
                bundle,
                activityCallback = object : ActivityResultCallback {
                    override fun onActivityResult(
                        requestCode: Int,
                        resultCode: Int,
                        data: Intent?
                    ) {
                        if (isGrantedWriteSettings) {
                            callback?.onResult(true)
                        } else {
                            callback?.onResult(false)
                        }
                    }

                })
        }

        /**
         *
         *
         * APP 是否可以 添加悬浮view
         *
         *
         * api 官网解释如下
         * Checks if the specified context can draw on top of other apps. As of API
         * * level 23, an app cannot draw on top of other apps unless it declares the
         * * [android.Manifest.permission.SYSTEM_ALERT_WINDOW] permission in its
         * * manifest.
         */
        @get:RequiresApi(api = Build.VERSION_CODES.M)
        val isGrantedDrawOverlays: Boolean
            get() = Settings.canDrawOverlays(sApplication)

        @RequiresApi(api = Build.VERSION_CODES.M)
        fun requestDrawOverlays(activity: FragmentActivity, callback: SimpleCallback?) {
            if (isGrantedDrawOverlays) {
                callback?.onResult(true)
                return
            }
            val bundle = bundleOf(Pair(TYPE, TYPE_DRAW_OVERLAYS))
            PermissionFragment.start(
                activity,
                bundle,
                activityCallback = object : ActivityResultCallback {
                    override fun onActivityResult(
                        requestCode: Int,
                        resultCode: Int,
                        data: Intent?
                    ) {
                        if (isGrantedDrawOverlays) {
                            callback?.onResult(true)
                        } else {
                            callback?.onResult(false)
                        }
                    }
                })
        }


        fun startIntentSenderForResult(
            activity: FragmentActivity,
            intentSender: IntentSender,
            callback: SimpleCallback
        ) {
            val bundle = Bundle()
            bundle.putInt(TYPE, TYPE_CREATE_MEDIA_REQUEST)
            bundle.putParcelable(EXTRA, intentSender)
            PermissionFragment.start(
                activity, bundle,
                activityCallback = object : ActivityResultCallback {
                    override fun onActivityResult(
                        requestCode: Int,
                        resultCode: Int,
                        data: Intent?
                    ) {
                        if (resultCode == Activity.RESULT_OK) {
                            callback.onResult(true)
                        } else {
                            callback.onResult(false)
                        }
                    }
                })
        }

        fun startActivityForResult(
            activity: FragmentActivity,
            intent: Intent,
            callback: ActivityResultCallback
        ) {
            val bundle = Bundle()
            bundle.putInt(TYPE, TYPE_START_ACTIVITY_FOR_RESULT)
            bundle.putParcelable(EXTRA, intent)
            PermissionFragment.start(
                activity,
                bundle,
                activityCallback = object : ActivityResultCallback {
                    override fun onActivityResult(
                        requestCode: Int,
                        resultCode: Int,
                        data: Intent?
                    ) {
                        callback.onActivityResult(requestCode, resultCode, data)
                    }
                })
        }

        /**
         * Launch the application's details settings.
         */
        fun launchAppDetailsSettings() {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:" + sApplication.packageName)
            if (!isIntentAvailable(intent)) return
            sApplication.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /**
         * 设置需要请求的权限数组
         *
         * @param permissions The permissions.
         * @return the single [HiPermission] instance
         *
         * @PermissionConstants.Permission
         */
        fun permission(vararg permissions: String): HiPermission {
            return HiPermission(*permissions)
        }

        @SuppressLint("QueryPermissionsNeeded")
        private fun isIntentAvailable(intent: Intent): Boolean {
            return sApplication
                .packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .size > 0
        }
    }
}