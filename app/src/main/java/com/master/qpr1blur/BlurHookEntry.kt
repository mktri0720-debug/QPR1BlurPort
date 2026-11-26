package com.master.qpr1blur

import android.app.Dialog
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BlurHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QPR1BlurPort]"

        // 模糊半径（像素）：推荐 60 ~ 120
        private const val BLUR_RADIUS_PX = 85

        // 背景遮罩不透明度（0~255）：原版为 255，推荐 165 ~ 195
        private const val PANEL_BG_ALPHA = 175
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，启用通用窗口与弹窗模糊引擎...")

        hookAllDialogs(lpparam.classLoader)
        hookWindowManagerGlobal(lpparam.classLoader)
    }

    /**
     * 方案一：通杀所有 SystemUI 内的 Dialog（涵盖新版/索尼定制音量面板与快捷设置二级菜单）
     */
    private fun hookAllDialogs(classLoader: ClassLoader) {
        XposedBridge.hookAllMethods(Dialog::class.java, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val dialog = param.thisObject as? Dialog ?: return
                    val className = dialog.javaClass.name
                    val window = dialog.window ?: return

                    // 过滤出音量面板、快捷设置弹窗及系统级对话框
                    if (className.contains("Volume", ignoreCase = true) ||
                        className.contains("SystemUIDialog", ignoreCase = true) ||
                        className.contains("QS", ignoreCase = true) ||
                        className.contains("Detail", ignoreCase = true)
                    ) {
                        applyWindowBlur(window, BLUR_RADIUS_PX)
                        window.decorView.let { decor ->
                            makeViewTreeTranslucent(decor, PANEL_BG_ALPHA, 0)
                        }
                        XposedBridge.log("$TAG 已为 Dialog 注入模糊: $className")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG Dialog Hook 异常: ${t.message}")
                }
            }
        })
    }

    /**
     * 方案二：底层 WindowManagerGlobal 拦截（专治非 Dialog 形态的独立悬浮音量条）
     */
    private fun hookWindowManagerGlobal(classLoader: ClassLoader) {
        val wmgClass = XposedHelpers.findClassIfExists(
            "android.view.WindowManagerGlobal",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(wmgClass, "addView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (param.args.size < 2) return
                    val view = param.args[0] as? View ?: return
                    val params = param.args[1] as? WindowManager.LayoutParams ?: return

                    val title = params.title?.toString() ?: ""
                    // 匹配音量面板窗口类型 (2020 = TYPE_VOLUME_OVERLAY) 或标题含 Volume 的悬浮层
                    val isVolumeWindow = params.type == 2020 ||
                            title.contains("Volume", ignoreCase = true)

                    if (isVolumeWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        params.format = PixelFormat.TRANSLUCENT
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        params.blurBehindRadius = 20

                        // 通过 ViewRootImpl/SurfaceControl 设置窗口自身背景模糊
                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                try {
                                    val viewRootImpl = XposedHelpers.callMethod(v, "getViewRootImpl")
                                    if (viewRootImpl != null) {
                                        XposedHelpers.callMethod(
                                            viewRootImpl,
                                            "setBackgroundBlurRadius",
                                            BLUR_RADIUS_PX
                                        )
                                    }
                                    makeViewTreeTranslucent(v, PANEL_BG_ALPHA, 0)
                                    XposedBridge.log("$TAG 已为悬浮音量窗口注入模糊: $title")
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG 悬浮窗口附加模糊异常: ${e.message}")
                                }
                            }

                            override fun onViewDetachedFromWindow(v: View) {}
                        })
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG WindowManagerGlobal Hook 异常: ${t.message}")
                }
            }
        })
    }

    private fun applyWindowBlur(window: Window, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    /**
     * 递归将外层容器背景转为半透明（深度限制 4 层，避免误伤内部文字与滑块进度条）
     */
    private fun makeViewTreeTranslucent(view: View, alpha: Int, depth: Int) {
        if (depth > 4) return
        view.background?.let { bg ->
            val mutated = bg.mutate()
            applyAlphaToDrawable(mutated, alpha)
            view.background = mutated
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                makeViewTreeTranslucent(view.getChildAt(i), alpha, depth + 1)
            }
        }
    }

    private fun applyAlphaToDrawable(drawable: Drawable, alpha: Int) {
        when (drawable) {
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    applyAlphaToDrawable(drawable.getDrawable(i), alpha)
                }
            }
            is GradientDrawable, is ColorDrawable -> {
                drawable.alpha = alpha
            }
            else -> {
                drawable.alpha = alpha
            }
        }
    }
}
