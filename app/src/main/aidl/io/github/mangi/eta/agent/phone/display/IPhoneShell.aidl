package io.github.mangi.eta.agent.phone.display;

import android.view.Surface;

/**
 * 运行在 Root app_process 助手进程里的常驻 Binder 服务。
 * Eta App 进程把 ImageReader / 接管小窗的 Surface 传过来，由助手建屏、注入输入、启动 App。
 */
interface IPhoneShell {
    // 在给定 Surface 上创建虚拟屏；失败返回 -1
    int createDisplay(in Surface surface, int width, int height, int dpi, String name) = 1;
    // 把虚拟屏输出切换到另一个 Surface（接管小窗 / 切回 ImageReader）；传 null 停止输出
    void setDisplaySurface(int displayId, in Surface surface) = 2;
    // 撤掉这块屏（DESTROY_CONTENT_ON_REMOVAL，其上的 App 一并结束）
    void releaseDisplay(int displayId) = 3;
    // 在这块屏上启动 App；activity 为空时用包的 launcher 入口。
    // 0=成功 1=找不到入口 2=出错 4=该 App 正在主屏（display 0）可见使用中，拒绝启动
    int startApp(int displayId, String packageName, String activity) = 4;
    // 注入按键（白名单内）；失败返回 false
    boolean injectKey(int displayId, int keyCode) = 5;
    boolean injectTap(int displayId, int x, int y) = 6;
    // 从 (startX,startY) 滑到 (endX,endY)，历时 durationMs
    boolean injectSwipe(int displayId, int startX, int startY, int endX, int endY, int durationMs) = 7;
    // 服务所在进程的 [uid, pid]；交接时核对
    int[] identity() = 8;
    // 撤掉本服务建的所有虚拟屏；进程不退出
    void releaseAll() = 9;
    // releaseAll 后退出进程
    void destroy() = 10;
    // 以 MIUI 小窗启动组件（真屏）：root 身份手动构造 options，
    // windowingMode=5 + bounds + setMiuiConfigFlag(2)，startActivityAsUser(callingPackage=launcher)。
    // 0=成功 1=找不到入口 2=出错 3=MIUI 小窗黑名单
    int startSmallWindow(String packageName, String activity) = 11;
    // 一根手指的一笔：按下、按 times 逐点移动、在最后一点抬起；事件到点才注入。
    // xs/ys/times 等长（2..1024 点），times[0]=0 且逐点不减、总时长 ≤10s；失败返回 false
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 12;
}
