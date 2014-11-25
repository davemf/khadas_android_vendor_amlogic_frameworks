package com.droidlogic.app;

import java.io.File;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera.CameraInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

public class UsbCameraManager {
    private static final String TAG             = "UsbCameraManager";

    private static final boolean DEBUG          = false;

    private static final String PACKAGES[]      = {
        "com.android.camera2",
    };

    private static final String ACTIVITIES[]    = {
        "com.android.camera.CameraLauncher",
    };

    private static final String SYS_TOKEN       = "android.hardware.ICameraService";
    public static final int REMOTE_EXCEPTION    = -0xffff;

    //must sync with ICameraService.h (frameworks\av\include\camera)
    int GET_NUMBER_OF_CAMERAS                   = IBinder.FIRST_CALL_TRANSACTION;
    int GET_CAMERA_INFO                         = IBinder.FIRST_CALL_TRANSACTION + 1;
    int CONNECT                                 = IBinder.FIRST_CALL_TRANSACTION + 2;
    int CONNECT_PRO                             = IBinder.FIRST_CALL_TRANSACTION + 3;
    int CONNECT_DEVICE                          = IBinder.FIRST_CALL_TRANSACTION + 4;
    int ADD_LISTENER                            = IBinder.FIRST_CALL_TRANSACTION + 5;
    int REMOVE_LISTENER                         = IBinder.FIRST_CALL_TRANSACTION + 6;
    int GET_CAMERA_CHARACTERISTICS              = IBinder.FIRST_CALL_TRANSACTION + 7;
    int GET_CAMERA_VENDOR_TAG_DESCRIPTOR        = IBinder.FIRST_CALL_TRANSACTION + 8;
    int GET_LEGACY_PARAMETERS                   = IBinder.FIRST_CALL_TRANSACTION + 9;
    int SUPPORTS_CAMERA_API                     = IBinder.FIRST_CALL_TRANSACTION + 10;
    int CONNECT_LEGACY                          = IBinder.FIRST_CALL_TRANSACTION + 11;

    int USB_CAMERA_ATTACH                       = IBinder.FIRST_CALL_TRANSACTION + 12;

    private Context mContext;
    private IBinder mIBinder = null;
    public UsbCameraManager(Context context){
        mContext = context;

        try {
            Object object = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", new Class[] { String.class })
                    .invoke(null, new Object[] { "media.camera" });
            mIBinder = (IBinder)object;
        }
        catch (Exception ex) {
            Log.e(TAG, "USB camera manager init fail:" + ex);
        }
    }

    private void usbCameraAttach(boolean isAttach){
        try {
            if (null != mIBinder) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                data.writeInterfaceToken(SYS_TOKEN);
                data.writeInt(isAttach?1:0);
                mIBinder.transact(USB_CAMERA_ATTACH, data, reply, 0);
                reply.recycle();
                data.recycle();
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "USB camera attach:" + ex);
        }
    }

    public void UsbDeviceAttach(UsbDevice device, boolean isAttach){
        if(isUsbCamera(device)){
            Log.i(TAG, "usb camera attach: " + isAttach);
            new VideoDevThread(mContext, isAttach).start();
        }
    }

    private boolean hasCamera() {
        int n = android.hardware.Camera.getNumberOfCameras();
        Log.i(TAG, "number of camera: " + n);
        return (n > 0);
    }

    private boolean hasBackCamera() {
        int n = android.hardware.Camera.getNumberOfCameras();
        CameraInfo info = new CameraInfo();
        for (int i = 0; i < n; i++) {
            android.hardware.Camera.getCameraInfo(i, info);
            if (info.facing == CameraInfo.CAMERA_FACING_BACK) {
                Log.i(TAG, "back camera found: " + i);
                return true;
            }
        }
        Log.i(TAG, "no back camera");
        return false;
    }

    private void disableComponent(String pkg, String klass) {
        ComponentName name = new ComponentName(pkg, klass);
        PackageManager pm = mContext.getPackageManager();

        // We need the DONT_KILL_APP flag, otherwise we will be killed
        // immediately because we are in the same app.
        pm.setComponentEnabledSetting(name,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    private void enableComponent(String pkg, String klass) {
        ComponentName name = new ComponentName(pkg, klass);
        PackageManager pm = mContext.getPackageManager();

        pm.setComponentEnabledSetting(name,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP);
    }

    public boolean isUsbCamera(UsbDevice device) {
        int count = device.getInterfaceCount();

        if(DEBUG){
            for (int i = 0; i < count; i++) {
                UsbInterface intf = device.getInterface(i);
                Log.i(TAG, "isCamera UsbInterface:" + intf);
            }
        }

        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            /*
            if (intf.getInterfaceClass() == android.hardware.usb.UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.getInterfaceSubclass() == 1 &&
                    intf.getInterfaceProtocol() == 1) {
                return true;
            }*/

            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    class VideoDevThread extends Thread {
        private static final String DEV_VIDEO_prefix = "/dev/video";
        private static final int DEV_NUM = 5;
        Context mContext;
        int mCamNum = 0;
        boolean mIsAttach;

        public VideoDevThread(Context context, boolean isAttach) {
            mContext = context;
            mIsAttach = isAttach;
            mCamNum = android.hardware.Camera.getNumberOfCameras();

            Log.i(TAG, "VideoDevThread isAttach:" + isAttach + ", cur camera num:" + mCamNum);
        }

        @Override
        public void run() {
            boolean end = false;
            int loopCount = 0;
            while( !end ){
                try{
                    Thread.sleep(500);//first delay 500ms, in order to wait kernel set up video device path
                }
                catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                }

                int devNum = 0;
                for( int i = 0; i < DEV_NUM; i++ ){
                    String path = DEV_VIDEO_prefix + i;
                    if(new File(path).exists()){
                        devNum++;
                    }
                }

                //Log.i(TAG, "video device num:" + devNum);
                if(mIsAttach && (devNum > mCamNum)){//device path has been set up by kernel
                    usbCameraAttach(mIsAttach);
                    for (int i = 0; i < ACTIVITIES.length; i++) {
                        enableComponent(PACKAGES[i], ACTIVITIES[i]);
                    }
                    end = true;
                }
                else if(!mIsAttach && (devNum < mCamNum)){//device path has been deleted by kernel
                    usbCameraAttach(mIsAttach);
                    for (int i = 0; i < ACTIVITIES.length; i++) {
                        disableComponent(PACKAGES[i], ACTIVITIES[i]);
                    }
                    end = true;
                }
                else if((mCamNum > 0) && (mCamNum == devNum)){//video device was plugged in when boot 
                    loopCount++;
                    if(loopCount > 2){//1s kernel has set up or delete the device path
                        end = true;
                    }
                }
            }
        }
    }
}