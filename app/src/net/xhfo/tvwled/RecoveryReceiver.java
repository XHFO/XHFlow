package net.xhfo.tvwled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Boot and a single inexact recovery attempt; Android force-stop is never bypassed. */
public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)&&!AmbientService.ACTION_RECOVER.equals(action))return;
        if(!AmbientService.mayRecover(context))return;
        try{
            AmbientService.dispatch(context,AmbientService.ACTION_RECOVER);
        }catch(RuntimeException failure){
            // Android 12+ can reject a background FGS start. Do not retry in a loop.
            Log.w("TVWLED","System denied background recovery",failure);
            AmbientService.idleError("后台恢复被系统限制，请手动打开XH光流");
        }
    }
}
