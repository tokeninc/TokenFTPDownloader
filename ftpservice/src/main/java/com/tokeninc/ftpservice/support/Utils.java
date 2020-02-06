package com.tokeninc.ftpservice.support;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;

import static android.net.ConnectivityManager.TYPE_WIFI;
import static com.tokeninc.ftpservice.instance.TokenFTPDownloader.FILE_SAVE_ARRAY;

public class Utils {
    public static boolean isWifiConnected(Context context) {
        NetworkInfo net = getActiveNetworkInfo(context);
        if(net != null)
        return (net.isConnected() && net.getType() == TYPE_WIFI);
        return false;
    }

    private static NetworkInfo getActiveNetworkInfo(Context context) {
        ConnectivityManager connManager = (ConnectivityManager)context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connManager != null)
        return connManager.getActiveNetworkInfo();
        return null;
    }

    /**
     * It can be null or empty string if no file downloaded via this download manager. It does not support
     * if file downloaded is deleted via root access or another method.
     * Therefore it just adds new downloaded files to list
     * @param context Application context
     * @return ArrayList<File> downloaded file list which are actually exists
     */
    public static @Nullable ArrayList<File> findDownloadedFileList(@NonNull Context context){
        ArrayList<String> list = SerializableManager.readSerializable(context,FILE_SAVE_ARRAY);
        ArrayList<File> tempList = new ArrayList<>();
        ArrayList<String> fileList = new ArrayList<>();
        if(list != null &&  list.size() > 0){
            for (String path : list) {
                File tempFile = doesFileExist(context,path);
                if(tempFile != null){
                    tempList.add(tempFile);
                    fileList.add(path);
                }
            }
        }
        SerializableManager.saveSerializable(context,fileList,FILE_SAVE_ARRAY);
        return tempList;
    }

    /**
     * File access is forbidden via full-path due to security issues,therefore we need context to find file actually exists
     * @param context Application Context
     * @param fileName Filename to find
     * @return
     */
    private static File doesFileExist(@NonNull Context context,@NonNull String fileName){
        String[] endPath = fileName.split(Environment.DIRECTORY_DOWNLOADS);
        File mainFile = new File(context.getFilesDir()+"/"+Environment.DIRECTORY_DOWNLOADS,endPath[1]);
        if(mainFile.exists()){
            return mainFile;
        }
        else{
            mainFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), endPath[1]);
            return mainFile.exists() ? mainFile : null;
        }
    }
}
