package com.tokeninc.ftpservice.service;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.tokeninc.ftpservice.instance.TokenFTPDownloader;
import com.tokeninc.ftpservice.support.RetryWithDelay;
import com.tokeninc.ftpservice.support.SerializableManager;
import com.tokeninc.ftpservice.ui.DiskPermissionActivity;
import com.tokeninc.ftpservice.model.FTPClientModel;
import com.tokeninc.ftpservice.model.FTPDownloadModel;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;

import io.reactivex.Completable;
import io.reactivex.CompletableObserver;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;

public class DownloadManager extends LifecycleService {

    private final String notificationChannelStringId = "token_ftp_downloader";
    private final String notificationChannelName = "Token FTP Downloader";
    private final int notificationNotifyId = 1503;
    private String filePath;
    private boolean writeToInternal = true;
    private boolean writeToExternal = false;
    private final DownloadManagerBinder binder = new DownloadManagerBinder();
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private BehaviorSubject<String> fileDownloadSubject = BehaviorSubject.create();
    private SingleEmitter<FTPClientModel> ftpClientEmitter;
    private FTPDownloadModel ftpDownloadModel;
    private double currentDownloadingFileSize;

    public class DownloadManagerBinder extends Binder{
        public DownloadManager getInstance(@NonNull FTPDownloadModel model){
            DownloadManager.this.ftpDownloadModel = model;
            if(model.isWriteToInternal()){
                DownloadManager.this.writeToInternal = true;
                DownloadManager.this.writeToExternal = false;
            }
            else{
                DownloadManager.this.writeToInternal = false;
                DownloadManager.this.writeToExternal = true;
            }
            return DownloadManager.this;
        }
    }

    /**
     * If battery is lower than 20%,stop download service
     * @return false if condition at top
     */

    private boolean checkBattery(){
        try{
            BatteryManager bm = (BatteryManager)getApplicationContext().getSystemService(Context.BATTERY_SERVICE);
            if(bm != null){
                int batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if(batLevel <= 20){
                    Log.e("TokenDownloader","Battery is low");
                    hideNotification();
                    Schedulers.shutdown();
                    stopSelf();
                    return false;
                }
            }
            return true;

        }catch (Exception e){
            Log.d("TokenDownloader","Error",e);
            e.printStackTrace();
            return true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if(intent.getAction() != null && intent.getAction().equals(Intent.ACTION_GET_CONTENT) && checkBattery()){
            ftpDownloadModel = ((FTPDownloadModel) intent.getSerializableExtra("model"));
            if(ContextCompat.checkSelfPermission(getApplicationContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                Intent myIntent = new Intent(this, DiskPermissionActivity.class);
                myIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                myIntent.setAction(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                startActivity(myIntent);
            }
            else{
                showNotification();
                connectFtp();
                Schedulers.start();
            }
        }
        else if(intent.getAction() != null &&
                intent.getAction().equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                && intent.getIntExtra("result",Activity.RESULT_CANCELED) == Activity.RESULT_OK && checkBattery()){
            showNotification();
            connectFtp();
            Schedulers.start();
        }
        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        super.onBind(intent);
        return binder;
    }

    private Single<FTPClientModel> ftpClient(String filePath){
        return Single.<FTPClientModel>create(emitter -> {
            try{
                FTPClient client = new FTPClient();
                client.connect(ftpDownloadModel.getIpAddress(),ftpDownloadModel.getPort());
                client.login(ftpDownloadModel.getUserName(),ftpDownloadModel.getPassword());
                client.setControlKeepAliveReplyTimeout(1000 * 60 * 60);
                client.setDataTimeout(1000 * 60 * 60);
                //client.enterLocalPassiveMode();
                client.setFileType(FTP.BINARY_FILE_TYPE);
                FTPClientModel model = new FTPClientModel(client,filePath);
                emitter.onSuccess(model);
            }catch (Exception e){
                emitter.onError(e);
            }
            ftpClientEmitter = emitter;
        }).subscribeOn(Schedulers.io()).observeOn(Schedulers.io()).retryWhen(
                new RetryWithDelay(5000,3));
    }

    private void saveDownloadedFilePath(){
        ArrayList<String> fileList = SerializableManager.readSerializable(getApplicationContext(), TokenFTPDownloader.FILE_SAVE_ARRAY);
        if(fileList == null){
            fileList = new ArrayList<>();
        }
        fileList.add(filePath);
        SerializableManager.saveSerializable(getApplicationContext(),fileList, TokenFTPDownloader.FILE_SAVE_ARRAY);
    }

    private Completable writeFile(FTPClientModel model){
        Completable completable = Completable.create(emitter -> {
            OutputStream outputStream = null;
            InputStream inputStream = null;
            try{
                File downloadFile = new File(filePath);
                outputStream = new BufferedOutputStream(new FileOutputStream(downloadFile));
                inputStream = model.getFtpClient().retrieveFileStream(model.getDownloadUrl());
                byte[] bytesArray = new byte[4096];
                int bytesRead = -1;
                int counter = 0;
                while ((bytesRead = inputStream.read(bytesArray)) != -1) {
                    counter += bytesRead;
                    updateProgress(counter);
                    outputStream.write(bytesArray, 0, bytesRead);
                }
                saveDownloadedFilePath();
                outputStream.close();
                inputStream.close();
                emitter.onComplete();
            }catch (Exception e){
                emitter.onError(e);
            }
            finally {
                if(outputStream != null){
                    outputStream.close();
                }
                if(inputStream != null){
                    inputStream.close();
                }
            }
        });
        return completable.subscribeOn(Schedulers.io()).observeOn(Schedulers.io());
    }

    int urlIndex = 0;
    public void connectFtp() {
        urlIndex = 0;
        fileDownloadSubject.observeOn(Schedulers.io()).observeOn(Schedulers.io()).subscribe(new Observer<String>() {
            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(String s) {
                ftpClient(s).subscribe(new SingleObserver<FTPClientModel>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(FTPClientModel ftpClientModel) {
                        try{
                            FTPFile file;
                            String fileName;
                            /**
                             * Filepath splitting to create actual file at Downloads folder
                             */
                            if(s.contains("/")){
                                String[] split = s.split("/");
                                fileName = split[split.length - 1];
                                file = ftpClientModel.getFtpClient().mlistFile(s);
                            }
                            else{
                                fileName = s;
                                file = ftpClientModel.getFtpClient().mlistFile(fileName);
                            }
                            Date date;
                            /**
                             * If last modified date cannot be found,assume for current time
                             * so it will be always lesser and it will download files from ftp
                             */
                            try{
                                date = new Date(file.getTimestamp().getTimeInMillis());
                            }catch(Exception e){
                                date = new Date();
                            }
                            /**
                             * Check if file exists and up-to-date,then no need to download
                             */
                            currentDownloadingFileSize = file.getSize();
                            if(doesFileExist(s,date)){
                                //This means files are same,we don't need to download
                                /**
                                 * Start downloading next or complete flow
                                 */
                                if(fileDownloadSubject != null){
                                    if(urlIndex + 1 < ftpDownloadModel.getDownloadFilePaths().size() && checkBattery()){
                                        updateNotification();
                                        fileDownloadSubject.onNext(ftpDownloadModel.getDownloadFilePaths().get(++urlIndex));
                                    }
                                    else{
                                        fileDownloadSubject.onComplete();
                                    }
                                }
                                else{
                                    onComplete();
                                }
                            }
                            else{
                                if(createFile(currentDownloadingFileSize,fileName)){
                                    writeFile(ftpClientModel).subscribe(new CompletableObserver() {
                                        @Override
                                        public void onSubscribe(Disposable d) {

                                        }

                                        @Override
                                        public void onComplete() {
                                            /**
                                             * Common procedure to close ftp connection which was active to restart again
                                             */
                                            try{
                                                ftpClientModel.getFtpClient().completePendingCommand();
                                                if(ftpClientModel.getFtpClient().isConnected()){
                                                    ftpClientModel.getFtpClient().disconnect();
                                                }
                                            }catch (IOException e){
                                                onError(e);
                                                return;
                                            }
                                            /**
                                             * Start downloading next or complete flow
                                             */
                                            if(fileDownloadSubject != null){
                                                if(urlIndex + 1 < ftpDownloadModel.getDownloadFilePaths().size() && checkBattery()){
                                                    updateNotification();
                                                    fileDownloadSubject.onNext(ftpDownloadModel.getDownloadFilePaths().get(++urlIndex));
                                                }
                                                else{
                                                    fileDownloadSubject.onComplete();
                                                }
                                            }
                                        }

                                        /**
                                         * On error we should remove notification,
                                         * shutdown threads and close android service and delete uncompleted file
                                         */

                                        @Override
                                        public void onError(Throwable e) {
                                            e.printStackTrace();
                                            if(filePath != null){
                                                File temp = new File(filePath);
                                                temp.delete();
                                            }
                                            hideNotification();
                                            Schedulers.shutdown();
                                            stopSelf();
                                        }
                                    });
                                }
                                /**
                                 * Common procedure to close ftp connection which was active to restart again
                                 */
                                else{
                                    ftpClientModel.getFtpClient().completePendingCommand();
                                    if(ftpClientModel.getFtpClient().isConnected()){
                                        ftpClientModel.getFtpClient().disconnect();
                                    }
                                    ftpClientEmitter.onError(new RuntimeException("Not enough capacity to download file"));
                                }
                            }
                        }catch (Exception e){
                            onError(e);
                        }
                    }

                    /**
                     * On error we should remove notification,
                     * shutdown threads and close android service and delete uncompleted file
                     */

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        if(filePath != null){
                            File temp = new File(filePath);
                            temp.delete();
                        }
                        hideNotification();
                        Schedulers.shutdown();
                        stopSelf();
                    }
                });

            }


            /**
             * On error we should remove notification,
             * shutdown threads and close android service and delete uncompleted file
             */

            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
                if(filePath != null){
                    File temp = new File(filePath);
                    temp.delete();
                }
                hideNotification();
                Schedulers.shutdown();
                stopSelf();
            }

            /**
             * Shutdown service if download is completed
             */

            @Override
            public void onComplete() {
                hideNotification();
                Schedulers.shutdown();
                stopSelf();
            }
        });
        fileDownloadSubject.onNext(ftpDownloadModel.getDownloadFilePaths().get(urlIndex));
    }

    /**
     *
     * @param sizeOfFiles It is required to check file size before we are be able to write it to disk
     * @param fileName is to create folders and required temporary file and to set disk writing path
     * @return true if file is successfully created
     */

    private boolean createFile(double sizeOfFiles,String fileName){
        File file;
        double convertToMega = sizeOfFiles / (1024*1024);
        if(getAvailableInternalMemorySize()/(1024*1024) > convertToMega && writeToInternal){
            file = new File(getApplicationContext().getFilesDir()+"/"+Environment.DIRECTORY_DOWNLOADS,fileName);
            if(!file.exists()){
                file.getAbsoluteFile().getParentFile().mkdirs();
            }
            this.filePath = file.getAbsolutePath();
        }
        else if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                && getAvailableExternalMemorySize()/(1024 * 1024) > convertToMega && writeToExternal){
            file = new File(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
            this.filePath = file.getAbsolutePath();
        }
        else{
            return false;
        }
        if (!file.exists()) {
            try{
                return file.createNewFile();
            }catch (Exception e){
                return false;
            }
        }
        return true;
    }

    /**
     * Check if file exists and up-to-date and file size is completely same,then no need to download
     * @param fileName
     * @param date
     * @return
     */

    private boolean doesFileExist(@NonNull String fileName,Date date){
        File mainFile = new File(getApplicationContext().getFilesDir()+"/"+Environment.DIRECTORY_DOWNLOADS,fileName);
        if(mainFile.exists()){
            return mainFile.lastModified() > date.getTime() && mainFile.length() == currentDownloadingFileSize;
        }
        mainFile = new File(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
        return mainFile.exists() && mainFile.lastModified() > date.getTime() && mainFile.length() == currentDownloadingFileSize;
    }

    public double getAvailableInternalMemorySize() {
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        long blockSize, availableBlocks;
        blockSize = stat.getBlockSizeLong();
        availableBlocks = stat.getAvailableBlocksLong();
        return availableBlocks * blockSize;
    }

    public double getAvailableExternalMemorySize() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File path = Environment.getExternalStorageDirectory();
            StatFs stat = new StatFs(path.getPath());
            long blockSize ,availableBlocks;
            blockSize = stat.getBlockSizeLong();
            availableBlocks = stat.getAvailableBlocksLong();
            return (availableBlocks * blockSize);
        } else {
            return 0;
        }
    }

    private void updateProgress(int length){
        if(notificationManager == null){
            notificationManager = (NotificationManager)getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        }
        notificationBuilder.setContentText(DecimalFormat.getPercentInstance().format((double)(length)/currentDownloadingFileSize));
        notificationBuilder.setProgress(100,(int)(length * 1.0/(currentDownloadingFileSize*0.01)),false);
        notificationManager.notify(notificationNotifyId,notificationBuilder.build());
    }

    private void updateNotification(){
        if(notificationManager == null){
            notificationManager = (NotificationManager)getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        }
        notificationBuilder.setContentTitle("Downloading " + (urlIndex + 1) +"/"+ftpDownloadModel.getDownloadFilePaths().size());
        notificationManager.notify(notificationNotifyId,notificationBuilder.build());
    }

    private void showNotification(){
        if(notificationManager == null){
            notificationManager = (NotificationManager)getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        }
        notificationBuilder = new NotificationCompat.Builder(getApplicationContext(),notificationChannelStringId);
        notificationBuilder.setOngoing(true);
        notificationBuilder.setAutoCancel(false);
        notificationBuilder.setOnlyAlertOnce(true);
        notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_download);
        notificationBuilder.setContentTitle("Downloading " + (urlIndex + 1) +"/"+ftpDownloadModel.getDownloadFilePaths().size());
        notificationBuilder.setContentText(DecimalFormat.getPercentInstance().format(0));
        notificationBuilder.setProgress(100, 0, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = notificationManager.getNotificationChannel(notificationChannelStringId);
            if (notificationChannel == null) {
                int importance = NotificationManager.IMPORTANCE_LOW;
                notificationChannel = new NotificationChannel(notificationChannelStringId, notificationChannelName, importance);
                notificationManager.createNotificationChannel(notificationChannel);
            }
        }
        startForeground(notificationNotifyId,notificationBuilder.build());
    }

    private void hideNotification(){
        stopForeground(true);
        if(notificationManager == null){
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if(notificationManager != null){
            notificationManager.cancel(notificationNotifyId);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Schedulers.shutdown();
        hideNotification();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Schedulers.shutdown();
        hideNotification();
    }

}
