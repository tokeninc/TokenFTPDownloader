package com.tokeninc.ftpservice.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;

public class FTPDownloadModel implements Serializable {

    private static final long serialVersionUID = 1500L;

    private String ipAddress;
    private int port;
    private String userName ;
    private String password ;
    private ArrayList<String> downloadFilePaths;
    private boolean writeToInternal;
    /**
     *
     * @param ipAddress "123.456.789.012" as example,no default option
     * @param port 5010 as example,no default option
     * @param userName if not given,'anonymous' will be default username to login
     * @param password if not given, empty string will be default password
     * @param downloadFilePaths file paths should be given from root.
     *                          Ex: '/myFiles/file.zip' or 'file.zip' or 'myFiles/file.zip' are all acceptable
     * @param writeToInternal writing into internal file system will prevent file leaks via connection from usb,
     *                        there is no option to extract it as a user however external file storage is easily
     *                        accessible by app user. default option if internal. Even without enough capacity,
     *                        this flag is overriding all alternatives
     *                        Ex: If internal storage does not have enough capacity and boolean is true,it will give
     *                        not enough capacity error instead of picking external disk.
     */

    public FTPDownloadModel(@NonNull String ipAddress, int port,
                            @Nullable String userName,@Nullable String password,@NonNull ArrayList<String> downloadFilePaths,
                            boolean writeToInternal){
        this.ipAddress = ipAddress;
        this.port = port;
        if(userName == null){
            this.userName = "anonymous";
        }
        else{
            this.userName = userName;
        }
        if(password == null){
            this.password = "";
        }
        else{
            this.password = password;
        }
        this.downloadFilePaths = downloadFilePaths;
        if(downloadFilePaths.size() == 0){
            throw new IllegalArgumentException("Download file path array cannot be empty");
        }
        this.writeToInternal = writeToInternal;
    }

    public int getPort() {
        return port;
    }

    public ArrayList<String> getDownloadFilePaths() {
        return downloadFilePaths;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getPassword() {
        return password;
    }

    public String getUserName() {
        return userName;
    }

    public boolean isWriteToInternal() {
        return writeToInternal;
    }
}
